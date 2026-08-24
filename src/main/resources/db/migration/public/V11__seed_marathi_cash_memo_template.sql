-- V11: Seed the Marathi Cash Memo bill template catalog row
INSERT INTO bill_templates (code, name, description, is_active)
VALUES (
    'MARATHI_CASH_MEMO_V1',
    'Marathi Cash Memo',
    'Mobile-shop style Marathi cash memo with GST and bank details footer',
    TRUE
);
