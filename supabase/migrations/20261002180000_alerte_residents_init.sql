-- =====================================================================================
-- Alerte Résidents — base partagée entre les appareils d'un établissement
--
-- Une base Supabase = un établissement. Elle ne contient QUE ce qui doit être partagé
-- entre les téléphones et les PC pour que les alertes soient coordonnées :
--   * incidents        : sorties de zone en cours / traitées (qui s'en occupe, retrouvé…)
--   * resident_pauses  : sorties accompagnées en cours (la surveillance est suspendue partout)
--   * devices          : postes connectés (pour savoir qui surveille)
--   * shared_config    : configuration de référence (zone, résidents, comptes Weenect dont
--                        les mots de passe sont chiffrés AVANT envoi par une phrase secrète)
-- Les positions GPS ne sont pas stockées en continu : chaque appareil interroge Weenect
-- lui-même. Seule la position au moment d'une sortie est gardée dans l'incident.
--
-- Accès : uniquement des comptes Supabase Auth inscrits dans la table « staff ».
-- La clé publique (anon / publishable) seule ne donne accès à rien, sauf au « keepalive ».
--
-- Idempotent, sans suppression d'objet : peut être rejoué sans risque (SQL Editor ou « supabase db push »).
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Membres autorisés
-- -------------------------------------------------------------------------------------
create table if not exists public.staff (
  user_id      uuid primary key references auth.users (id) on delete cascade,
  display_name text not null default '',
  created_at   timestamptz not null default now()
);
comment on table public.staff is
  'Comptes autorisés à utiliser la base. Ajout : select public.add_staff(''email'', ''Nom du poste'');';

-- Fonctions internes : schéma « private », non exposé par l'API REST.
create schema if not exists private;
grant usage on schema private to authenticated;

create or replace function private.is_staff()
returns boolean
language sql stable security definer set search_path = ''
as $$
  select exists (select 1 from public.staff s where s.user_id = (select auth.uid()));
$$;
revoke execute on function private.is_staff() from public, anon;
grant execute on function private.is_staff() to authenticated;

-- Ajout d'un membre depuis le SQL Editor (réservé à l'administrateur du projet).
create or replace function public.add_staff(p_email text, p_display_name text default '')
returns uuid
language plpgsql security definer set search_path = ''
as $$
declare
  v_id uuid;
begin
  select u.id into v_id from auth.users u where lower(u.email) = lower(trim(p_email));
  if v_id is null then
    raise exception 'Aucun utilisateur Auth avec l''e-mail %. Créez-le d''abord (Authentication > Users > Add user).', p_email;
  end if;
  insert into public.staff (user_id, display_name) values (v_id, coalesce(p_display_name, ''))
  on conflict (user_id) do update set display_name = excluded.display_name;
  return v_id;
end;
$$;

-- -------------------------------------------------------------------------------------
-- Tables partagées
-- -------------------------------------------------------------------------------------
create or replace function public.touch_updated_at()
returns trigger
language plpgsql set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

create table if not exists public.devices (
  id              uuid primary key,
  user_id         uuid not null default auth.uid() references auth.users (id) on delete cascade,
  name            text not null,
  platform        text not null default 'other' check (platform in ('android', 'windows', 'other')),
  app_version     text,
  monitoring_ok   boolean not null default true,
  residents_count integer not null default 0,
  last_seen_at    timestamptz not null default now(),
  created_at      timestamptz not null default now()
);
comment on table public.devices is 'Téléphones et PC connectés (mis à jour toutes les 30 s environ).';

create table if not exists public.incidents (
  id                    uuid primary key default gen_random_uuid(),
  tracker_id            bigint not null,
  resident_name         text not null,
  is_drill              boolean not null default false,
  status                text not null default 'active' check (status in ('active', 'handling', 'resolved')),
  opened_at             timestamptz not null default now(),
  opened_by_device      uuid references public.devices (id) on delete set null,
  opened_by_device_name text,
  latitude              double precision,
  longitude             double precision,
  distance_m            double precision,
  handled_by            text,
  handled_at            timestamptz,
  resolved_by           text,
  resolved_at           timestamptz,
  resolution            text check (resolution in ('found', 'returned', 'outing', 'cancelled')),
  updated_at            timestamptz not null default now()
);
comment on table public.incidents is
  'Sorties de zone. Un seul incident ouvert par balise (et par mode exercice). tracker_id = identifiant de la balise Weenect.';
create unique index if not exists incidents_one_open_per_tracker
  on public.incidents (tracker_id, is_drill) where status <> 'resolved';
create index if not exists incidents_updated_at on public.incidents (updated_at);
create index if not exists incidents_opened_by_device on public.incidents (opened_by_device);
create index if not exists devices_user_id on public.devices (user_id);

create table if not exists public.resident_pauses (
  tracker_id    bigint primary key,
  resident_name text not null,
  paused_until  timestamptz,
  reason        text,
  set_by        text,
  updated_at    timestamptz not null default now()
);
comment on table public.resident_pauses is
  'Sorties accompagnées : surveillance suspendue jusqu''à paused_until (null = pas de sortie en cours).';

create table if not exists public.shared_config (
  id           smallint primary key default 1 check (id = 1),
  payload      text not null,
  published_by text,
  published_at timestamptz not null default now()
);
comment on table public.shared_config is
  'Export de configuration de l''app (format JSON de la sauvegarde). Les mots de passe Weenect y sont chiffrés côté appareil par une phrase secrète que Supabase ne connaît pas.';

create table if not exists public.keepalive (
  id        smallint primary key default 1 check (id = 1),
  pinged_at timestamptz not null default now(),
  pings     bigint not null default 0
);
comment on table public.keepalive is 'Activité régulière (action GitHub) pour éviter la mise en pause des projets gratuits.';

create or replace trigger incidents_touch before update on public.incidents
  for each row execute function public.touch_updated_at();
create or replace trigger resident_pauses_touch before update on public.resident_pauses
  for each row execute function public.touch_updated_at();

-- -------------------------------------------------------------------------------------
-- Sécurité : RLS partout, accès réservé aux membres
-- -------------------------------------------------------------------------------------
alter table public.staff           enable row level security;
alter table public.devices         enable row level security;
alter table public.incidents       enable row level security;
alter table public.resident_pauses enable row level security;
alter table public.shared_config   enable row level security;
alter table public.keepalive       enable row level security;

-- Une policy par usage ; créées si absentes, sinon mises à jour (script rejouable sans suppression).
do $$
declare
  p record;
begin
  for p in
    select * from (values
      ('staff',           'staff_select',     'select', 'using ((select private.is_staff()))'),
      ('devices',         'devices_all',      'all',    'using ((select private.is_staff())) with check ((select private.is_staff()))'),
      ('incidents',       'incidents_select', 'select', 'using ((select private.is_staff()))'),
      ('incidents',       'incidents_insert', 'insert', 'with check ((select private.is_staff()))'),
      ('incidents',       'incidents_update', 'update', 'using ((select private.is_staff())) with check ((select private.is_staff()))'),
      ('resident_pauses', 'pauses_all',       'all',    'using ((select private.is_staff())) with check ((select private.is_staff()))'),
      ('shared_config',   'config_all',       'all',    'using ((select private.is_staff())) with check ((select private.is_staff()))')
    ) as t(tbl, name, cmd, expr)
  loop
    if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = p.tbl and policyname = p.name) then
      execute format('create policy %I on public.%I for %s to authenticated %s', p.name, p.tbl, p.cmd, p.expr);
    else
      execute format('alter policy %I on public.%I to authenticated %s', p.name, p.tbl, p.expr);
    end if;
  end loop;
end;
$$;

-- keepalive : la clé publique peut seulement mettre à jour l'unique ligne d'horodatage.
do $$
begin
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'keepalive' and policyname = 'keepalive_ping') then
    create policy keepalive_ping on public.keepalive for all to anon, authenticated using (id = 1) with check (id = 1);
  end if;
end;
$$;

revoke all on public.staff, public.devices, public.incidents, public.resident_pauses,
  public.shared_config, public.keepalive from anon;
grant select, insert, update on public.keepalive to anon, authenticated;

-- -------------------------------------------------------------------------------------
-- Fonctions appelées par l'application (RLS appliquée : security invoker)
-- -------------------------------------------------------------------------------------
create or replace function public.require_staff()
returns void
language plpgsql stable set search_path = ''
as $$
begin
  if not private.is_staff() then
    raise exception 'Compte non autorisé : ajoutez-le à la table staff' using errcode = '42501';
  end if;
end;
$$;

-- Vérifie la connexion : renvoie {"staff": true/false, "display_name": "..."}
-- (security invoker : un compte non membre ne voit aucune ligne de staff → false)
create or replace function public.check_access()
returns jsonb
language sql stable set search_path = ''
as $$
  select jsonb_build_object(
    'staff', exists (select 1 from public.staff s where s.user_id = (select auth.uid())),
    'display_name', (select s.display_name from public.staff s where s.user_id = (select auth.uid()))
  );
$$;

-- Une balise est sortie : ouvre l'incident, ou renvoie celui déjà ouvert par un autre appareil.
create or replace function public.report_exit(
  p_tracker_id bigint, p_resident_name text, p_is_drill boolean,
  p_latitude double precision, p_longitude double precision, p_distance_m double precision,
  p_device_id uuid, p_device_name text
)
returns public.incidents
language plpgsql set search_path = ''
as $$
declare
  v public.incidents;
begin
  perform public.require_staff();
  insert into public.incidents (tracker_id, resident_name, is_drill, latitude, longitude, distance_m,
                                opened_by_device, opened_by_device_name)
  values (p_tracker_id, p_resident_name, coalesce(p_is_drill, false), p_latitude, p_longitude, p_distance_m,
          (select d.id from public.devices d where d.id = p_device_id), p_device_name)
  on conflict (tracker_id, is_drill) where status <> 'resolved' do nothing;

  select * into v from public.incidents i
   where i.tracker_id = p_tracker_id and i.is_drill = coalesce(p_is_drill, false) and i.status <> 'resolved';
  return v;
end;
$$;

-- « Je m'en occupe »
create or replace function public.handle_incident(p_tracker_id bigint, p_is_drill boolean, p_staff text)
returns public.incidents
language plpgsql set search_path = ''
as $$
declare
  v public.incidents;
begin
  perform public.require_staff();
  update public.incidents i
     set status = 'handling', handled_by = p_staff, handled_at = now()
   where i.tracker_id = p_tracker_id and i.is_drill = coalesce(p_is_drill, false) and i.status = 'active'
  returning * into v;
  if v.id is null then
    select * into v from public.incidents i
     where i.tracker_id = p_tracker_id and i.is_drill = coalesce(p_is_drill, false) and i.status <> 'resolved';
  end if;
  return v;
end;
$$;

-- Clôture : retrouvé par un soignant, retour confirmé par la balise, sortie accompagnée…
create or replace function public.resolve_incident(p_tracker_id bigint, p_is_drill boolean, p_staff text, p_resolution text)
returns public.incidents
language plpgsql set search_path = ''
as $$
declare
  v public.incidents;
begin
  perform public.require_staff();
  update public.incidents i
     set status = 'resolved', resolved_by = p_staff, resolved_at = now(), resolution = p_resolution
   where i.tracker_id = p_tracker_id and i.is_drill = coalesce(p_is_drill, false) and i.status <> 'resolved'
  returning * into v;
  return v;
end;
$$;

-- Début (p_until renseigné) ou fin (p_until null) d'une sortie accompagnée.
create or replace function public.set_pause(
  p_tracker_id bigint, p_resident_name text, p_until timestamptz, p_reason text, p_staff text
)
returns public.resident_pauses
language plpgsql set search_path = ''
as $$
declare
  v public.resident_pauses;
begin
  perform public.require_staff();
  insert into public.resident_pauses (tracker_id, resident_name, paused_until, reason, set_by)
  values (p_tracker_id, p_resident_name, p_until, p_reason, p_staff)
  on conflict (tracker_id) do update
     set resident_name = excluded.resident_name, paused_until = excluded.paused_until,
         reason = excluded.reason, set_by = excluded.set_by
  returning * into v;
  if p_until is not null then
    update public.incidents i
       set status = 'resolved', resolved_by = p_staff, resolved_at = now(), resolution = 'outing'
     where i.tracker_id = p_tracker_id and not i.is_drill and i.status <> 'resolved';
  end if;
  return v;
end;
$$;

-- Appelée toutes les quelques secondes par chaque appareil : signale le poste et renvoie
-- l'état partagé (incidents ouverts + changements récents, sorties, postes, version de config).
create or replace function public.sync_state(
  p_device_id uuid, p_device_name text, p_platform text, p_app_version text,
  p_monitoring_ok boolean, p_residents_count integer, p_since timestamptz
)
returns jsonb
language plpgsql set search_path = ''
as $$
declare
  v_since timestamptz := coalesce(p_since, now() - interval '1 day');
begin
  perform public.require_staff();

  insert into public.devices as d (id, user_id, name, platform, app_version, monitoring_ok, residents_count, last_seen_at)
  values (p_device_id, (select auth.uid()), p_device_name,
          case when p_platform in ('android', 'windows') then p_platform else 'other' end,
          p_app_version, coalesce(p_monitoring_ok, true), coalesce(p_residents_count, 0), now())
  on conflict (id) do update
     set user_id = excluded.user_id, name = excluded.name, platform = excluded.platform,
         app_version = excluded.app_version, monitoring_ok = excluded.monitoring_ok,
         residents_count = excluded.residents_count, last_seen_at = now()
   where d.last_seen_at < now() - interval '30 seconds'
      or d.monitoring_ok is distinct from excluded.monitoring_ok
      or d.name is distinct from excluded.name;

  return jsonb_build_object(
    'now', now(),
    'incidents', coalesce((
      select jsonb_agg(to_jsonb(i) order by i.updated_at)
        from public.incidents i
       where i.status <> 'resolved' or i.updated_at >= v_since), '[]'::jsonb),
    'pauses', coalesce((
      select jsonb_agg(to_jsonb(p) order by p.updated_at)
        from public.resident_pauses p
       where p.paused_until > now() or p.updated_at >= v_since), '[]'::jsonb),
    'devices', coalesce((
      select jsonb_agg(jsonb_build_object(
               'id', d.id, 'name', d.name, 'platform', d.platform, 'app_version', d.app_version,
               'monitoring_ok', d.monitoring_ok, 'residents_count', d.residents_count,
               'last_seen_at', d.last_seen_at) order by d.name)
        from public.devices d
       where d.last_seen_at > now() - interval '1 day'), '[]'::jsonb),
    'config', (select jsonb_build_object('published_by', c.published_by, 'published_at', c.published_at)
                 from public.shared_config c where c.id = 1)
  );
end;
$$;

create or replace function public.publish_config(p_payload text, p_published_by text)
returns timestamptz
language plpgsql set search_path = ''
as $$
declare
  v timestamptz;
begin
  perform public.require_staff();
  insert into public.shared_config (id, payload, published_by, published_at)
  values (1, p_payload, p_published_by, now())
  on conflict (id) do update
     set payload = excluded.payload, published_by = excluded.published_by, published_at = now()
  returning published_at into v;
  return v;
end;
$$;

create or replace function public.get_shared_config()
returns jsonb
language plpgsql stable set search_path = ''
as $$
begin
  perform public.require_staff();
  return (select jsonb_build_object('payload', c.payload, 'published_by', c.published_by, 'published_at', c.published_at)
            from public.shared_config c where c.id = 1);
end;
$$;

-- Activité régulière pour l'action GitHub (seule fonction ouverte à la clé publique).
-- N'expose aucune donnée : renvoie juste l'heure du ping (security invoker + policy keepalive_ping).
create or replace function public.keepalive()
returns timestamptz
language sql set search_path = ''
as $$
  insert into public.keepalive (id, pinged_at, pings) values (1, now(), 1)
  on conflict (id) do update set pinged_at = now(), pings = public.keepalive.pings + 1
  returning pinged_at;
$$;

-- Conservation des données (RGPD) : supprime les incidents clos et postes inactifs anciens.
-- À lancer depuis le SQL Editor, par ex. : select public.purge_history(365);
create or replace function public.purge_history(p_days integer default 365)
returns integer
language plpgsql security definer set search_path = ''
as $$
declare
  n integer;
begin
  delete from public.incidents where status = 'resolved' and updated_at < now() - make_interval(days => p_days);
  get diagnostics n = row_count;
  delete from public.resident_pauses where paused_until is null and updated_at < now() - make_interval(days => p_days);
  delete from public.devices where last_seen_at < now() - make_interval(days => p_days);
  return n;
end;
$$;

-- -------------------------------------------------------------------------------------
-- Droits d'exécution : rien pour le public, sauf keepalive()
-- -------------------------------------------------------------------------------------
revoke execute on function
  public.add_staff(text, text), public.touch_updated_at(), public.require_staff(),
  public.check_access(),
  public.report_exit(bigint, text, boolean, double precision, double precision, double precision, uuid, text),
  public.handle_incident(bigint, boolean, text), public.resolve_incident(bigint, boolean, text, text),
  public.set_pause(bigint, text, timestamptz, text, text),
  public.sync_state(uuid, text, text, text, boolean, integer, timestamptz),
  public.publish_config(text, text), public.get_shared_config(), public.keepalive(), public.purge_history(integer)
from public, anon, authenticated;

grant execute on function
  public.require_staff(), public.check_access(),
  public.report_exit(bigint, text, boolean, double precision, double precision, double precision, uuid, text),
  public.handle_incident(bigint, boolean, text), public.resolve_incident(bigint, boolean, text, text),
  public.set_pause(bigint, text, timestamptz, text, text),
  public.sync_state(uuid, text, text, text, boolean, integer, timestamptz),
  public.publish_config(text, text), public.get_shared_config()
to authenticated;

grant execute on function public.keepalive() to anon, authenticated;
