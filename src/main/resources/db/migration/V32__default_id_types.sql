-- The ID types a stylist picks from at sign-up. Without at least one, the
-- sign-up ID step cannot be completed. Seeded only when the table is empty,
-- so a database that already has its own list (the demo showroom) keeps it,
-- and admins can still add, rename or remove types afterwards.
INSERT INTO identifications (identification_name, status, inserted_date)
SELECT t.name, '0', CURDATE()
FROM (
    SELECT 1 AS pos, 'Driver''s licence' AS name
    UNION ALL SELECT 2, 'Passport'
    UNION ALL SELECT 3, 'Provincial or territorial photo ID card'
    UNION ALL SELECT 4, 'Permanent resident card'
    UNION ALL SELECT 5, 'Secure certificate of Indian status'
) t
WHERE NOT EXISTS (SELECT 1 FROM identifications)
ORDER BY t.pos;
