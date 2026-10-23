-- Signing-key rotation re-signs published revisions. Their content (body,
-- payload, digest and every other column) stays immutable; only the
-- signature and the ID of the key that made it may change.
create or replace function forbid_published_revision_update() returns trigger language plpgsql as $$
begin
  if old.state = 'published' and (to_jsonb(new) - 'signature' - 'key_id') is distinct from (to_jsonb(old) - 'signature' - 'key_id') then
    raise exception 'published challenge revisions are immutable' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
