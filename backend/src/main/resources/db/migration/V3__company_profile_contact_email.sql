ALTER TABLE company_profiles
    ADD COLUMN contact_email VARCHAR(320);

UPDATE company_profiles AS profile
SET contact_email = users.email
FROM users
WHERE users.id = profile.user_id;
