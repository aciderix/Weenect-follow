-- Facultatif : purge automatique de l'historique (RGPD), après la migration principale.
-- Nécessite l'extension pg_cron (disponible sur supabase.com ; à vérifier si auto-hébergé).
-- Chaque dimanche à 3 h 17 (UTC) : supprime les incidents clos, les sorties terminées et les
-- appareils inactifs depuis plus de 365 jours. Les alertes en cours ne sont jamais touchées.
-- Adaptez la durée à votre politique de conservation (à valider avec votre DPO) puis relancez :
-- la planification du même nom est remplacée.

create extension if not exists pg_cron;

select cron.schedule(
  'purge-alerte-residents',
  '17 3 * * 0',
  $$select public.purge_history(365)$$
);

-- Contrôle :   select * from cron.job;
--              select status, start_time, return_message from cron.job_run_details order by start_time desc limit 5;
-- Arrêt :      select cron.unschedule('purge-alerte-residents');
