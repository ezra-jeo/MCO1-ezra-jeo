-- Existing kingpay schemas created from the original unique=true mapping only.
-- Check the index inventory first. Skip when uk_users_username already exists.
ALTER TABLE kingpay.users RENAME INDEX UKr43af9ap4edm43mmtq01oddj6 TO uk_users_username;
