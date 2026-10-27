-- Pair this with reverting the named-constraint application change.
-- Renaming preserves uniqueness and every stored row.
ALTER TABLE kingpay.users RENAME INDEX uk_users_username TO UKr43af9ap4edm43mmtq01oddj6;
