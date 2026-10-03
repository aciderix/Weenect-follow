-- =====================================================================================
-- Alerte Résidents 2.1 — synchronisation automatique de la configuration
--
-- Un résident (ou un compte Weenect, ou la zone) ajouté ou modifié sur un appareil se
-- retrouve sur tous les autres en quelques secondes. Chaque ligne a un identifiant (uuid)
-- généré par l'appareil qui l'a créée ; le dernier enregistrement gagne (updated_at serveur).
-- Une fiche retirée n'est pas effacée tout de suite : elle est marquée « removed » pour que
-- les autres appareils la retirent aussi.
--
-- Mots de passe Weenect : jamais en clair. « password_enc » est chiffré sur l'appareil par la
-- phrase secrète de l'établissement (PBKDF2 + AES-GCM), que Supabase ne connaît pas.
-- « passphrase_check » permet seulement de vérifier qu'une phrase saisie est la bonne.
--
-- Idempotent, sans suppression d'objet : peut être rejoué sans risque.
-- Prérequis : 20261002180000_alerte_residents_init.sql
-- =====================================================================================

create table if not exists public.weenect_accounts (
  id           uuid primary key,
  label        text not null default '',
  username     text not null,
  password_enc text,
  removed      boolean not null default false,
  updated_by   text,
  updated_at   timestamptz not null default now()
);
comment on table public.weenect_accounts is
  'Comptes Weenect partagés. password_enc : chiffré côté appareil par la phrase secrète de l''établissement.';

create table if not exists public.residents (
  id                 uuid primary key,
  name               text not null,
  room_number        text not null default '',
  unit               text not null default '',
  avatar_color       text not null default '#1E88E5',
  tracker_id         bigint,
  tracker_name       text,
  account_id         uuid,
  emergency_contact  text not null default '',
  notes              text not null default '',
  risk_level         integer not null default 0,
  is_tracking_active boolean not null default true,
  removed            boolean not null default false,
  updated_by         text,
  updated_at         timestamptz not null default now()
);
comment on table public.residents is
  'Fiches résidents partagées entre appareils (sans photo ni position). account_id = weenect_accounts.id.';

create table if not exists public.facility_settings (
  id               smallint primary key default 1 check (id = 1),
  zone             jsonb,
  zone_updated_at  timestamptz,
  passphrase_check text,
  updated_by       text
);
comment on table public.facility_settings is
  'Zone de l''établissement (même format que l''export de l''app) et contrôle de la phrase secrète.';

create index if not exists residents_updated_at on public.residents (updated_at);
create index if not exists weenect_accounts_updated_at on public.weenect_accounts (updated_at);

create or replace trigger residents_touch before update on public.residents
  for each row execute function public.touch_updated_at();
create or replace trigger weenect_accounts_touch before update on public.weenect_accounts
  for each row execute function public.touch_updated_at();

alter table public.residents         enable row level security;
alter table public.weenect_accounts  enable row level security;
alter table public.facility_settings enable row level security;

do $$
declare
  p record;
begin
  for p in
    select * from (values
      ('residents',         'residents_all'),
      ('weenect_accounts',  'accounts_all'),
      ('facility_settings', 'settings_all')
    ) as t(tbl, name)
  loop
    if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = p.tbl and policyname = p.name) then
      execute format('create policy %I on public.%I for all to authenticated using ((select private.is_staff())) with check ((select private.is_staff()))', p.name, p.tbl);
    end if;
  end loop;
end;
$$;

revoke all on public.residents, public.weenect_accounts, public.facility_settings from anon;

-- -------------------------------------------------------------------------------------
-- Fonctions (security invoker : la RLS s'applique)
-- -------------------------------------------------------------------------------------

-- Crée ou met à jour une fiche résident. p : objet JSON avec les colonnes de la table.
create or replace function public.save_resident(p jsonb, p_by text)
returns timestamptz
language plpgsql set search_path = ''
as $$
declare
  v timestamptz;
begin
  perform public.require_staff();
  insert into public.residents as r (id, name, room_number, unit, avatar_color, tracker_id, tracker_name, account_id,
                                     emergency_contact, notes, risk_level, is_tracking_active, removed, updated_by)
  values ((p->>'id')::uuid, coalesce(p->>'name', ''), coalesce(p->>'room_number', ''), coalesce(p->>'unit', ''),
          coalesce(p->>'avatar_color', '#1E88E5'), (p->>'tracker_id')::bigint, p->>'tracker_name',
          (p->>'account_id')::uuid, coalesce(p->>'emergency_contact', ''), coalesce(p->>'notes', ''),
          coalesce((p->>'risk_level')::integer, 0), coalesce((p->>'is_tracking_active')::boolean, true),
          coalesce((p->>'removed')::boolean, false), p_by)
  on conflict (id) do update
     set name = excluded.name, room_number = excluded.room_number, unit = excluded.unit,
         avatar_color = excluded.avatar_color, tracker_id = excluded.tracker_id, tracker_name = excluded.tracker_name,
         account_id = excluded.account_id, emergency_contact = excluded.emergency_contact, notes = excluded.notes,
         risk_level = excluded.risk_level, is_tracking_active = excluded.is_tracking_active,
         removed = excluded.removed, updated_by = excluded.updated_by
  returning r.updated_at into v;
  return v;
end;
$$;

-- Crée ou met à jour un compte Weenect. Sans « password_enc », le mot de passe déjà partagé est conservé.
create or replace function public.save_account(p jsonb, p_by text)
returns timestamptz
language plpgsql set search_path = ''
as $$
declare
  v timestamptz;
begin
  perform public.require_staff();
  insert into public.weenect_accounts as a (id, label, username, password_enc, removed, updated_by)
  values ((p->>'id')::uuid, coalesce(p->>'label', ''), coalesce(p->>'username', ''), p->>'password_enc',
          coalesce((p->>'removed')::boolean, false), p_by)
  on conflict (id) do update
     set label = excluded.label, username = excluded.username,
         password_enc = coalesce(excluded.password_enc, a.password_enc),
         removed = excluded.removed, updated_by = excluded.updated_by
  returning a.updated_at into v;
  return v;
end;
$$;

create or replace function public.save_zone(p_zone jsonb, p_by text)
returns timestamptz
language plpgsql set search_path = ''
as $$
declare
  v timestamptz;
begin
  perform public.require_staff();
  insert into public.facility_settings as s (id, zone, zone_updated_at, updated_by)
  values (1, p_zone, now(), p_by)
  on conflict (id) do update set zone = excluded.zone, zone_updated_at = now(), updated_by = excluded.updated_by
  returning s.zone_updated_at into v;
  return v;
end;
$$;

-- Enregistre le contrôle de la phrase secrète s'il n'existe pas encore ; renvoie celui en place.
create or replace function public.init_passphrase_check(p_check text)
returns text
language plpgsql set search_path = ''
as $$
declare
  v text;
begin
  perform public.require_staff();
  insert into public.facility_settings as s (id, passphrase_check) values (1, p_check)
  on conflict (id) do update set passphrase_check = coalesce(s.passphrase_check, excluded.passphrase_check)
  returning s.passphrase_check into v;
  return v;
end;
$$;

-- Configuration modifiée depuis p_since (tout si null).
create or replace function public.sync_config(p_since timestamptz)
returns jsonb
language plpgsql stable set search_path = ''
as $$
begin
  perform public.require_staff();
  return jsonb_build_object(
    'now', now(),
    'residents', coalesce((
      select jsonb_agg(to_jsonb(r) order by r.updated_at)
        from public.residents r
       where p_since is null or r.updated_at >= p_since), '[]'::jsonb),
    'accounts', coalesce((
      select jsonb_agg(to_jsonb(a) order by a.updated_at)
        from public.weenect_accounts a
       where p_since is null or a.updated_at >= p_since), '[]'::jsonb),
    'zone', (select s.zone from public.facility_settings s where s.id = 1),
    'zone_updated_at', (select s.zone_updated_at from public.facility_settings s where s.id = 1),
    'passphrase_check', (select s.passphrase_check from public.facility_settings s where s.id = 1)
  );
end;
$$;

revoke execute on function
  public.save_resident(jsonb, text), public.save_account(jsonb, text), public.save_zone(jsonb, text),
  public.init_passphrase_check(text), public.sync_config(timestamptz)
from public, anon;

grant execute on function
  public.save_resident(jsonb, text), public.save_account(jsonb, text), public.save_zone(jsonb, text),
  public.init_passphrase_check(text), public.sync_config(timestamptz)
to authenticated;
