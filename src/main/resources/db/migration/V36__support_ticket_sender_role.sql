-- Stylists can open support tickets too. Customer and stylist ids share one id
-- space, so the id alone already names the account; the role says which table
-- it lives in. Every ticket before this change came from a customer.
ALTER TABLE support_tickets ADD COLUMN sender_role VARCHAR(16) NULL;
UPDATE support_tickets SET sender_role = 'CUSTOMER' WHERE sender_role IS NULL;
