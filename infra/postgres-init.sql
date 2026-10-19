-- Separate runtime and migration roles: the app connects as wakeme_app, which
-- can read and write rows but cannot change schema. Migrations run as the
-- owner (wakeme). In managed PostgreSQL (e.g. Supabase) create the same split
-- and never give the API the service-role key, which bypasses RLS.
create database wakeme_test owner wakeme;
create role wakeme_app login password 'wakeme_app' nosuperuser nocreatedb nocreaterole noinherit;
grant connect on database wakeme to wakeme_app;
\connect wakeme
alter default privileges for role wakeme in schema public grant select, insert, update, delete on tables to wakeme_app;
alter default privileges for role wakeme in schema public grant usage, select on sequences to wakeme_app;
alter default privileges for role wakeme in schema public grant execute on functions to wakeme_app;
grant usage on schema public to wakeme_app;
