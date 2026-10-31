-- Sync feed sequence numbers follow commit order.
--
-- seq used to be fixed when a row was inserted. Two transactions appending
-- for the same user could commit in the opposite order, and a phone that
-- synced in between moved its cursor past the slower change and never
-- received it. Each row now takes its final seq at commit, under one short
-- transaction-scoped lock (held from the first row's renumbering until the
-- commit finishes), so a later commit always gets a higher seq than every
-- change already visible to readers.
create function sync_events_commit_seq() returns trigger language plpgsql as $$
begin
  perform pg_advisory_xact_lock(7428935201); -- fixed key: sync feed commit order
  update sync_events set seq = default where seq = new.seq;
  return null;
end;
$$;

create constraint trigger sync_events_commit_order after insert on sync_events
  deferrable initially deferred
  for each row execute function sync_events_commit_seq();
