<?php

return [
    'login_max_attempts' => (int) env('AUTH_RATE_LIMIT_MAX', 5),
    'login_window_minutes' => (int) env('AUTH_RATE_LIMIT_WINDOW_MINUTES', 15),
    'cors_allowed_origins' => array_values(array_filter(array_map(
        'trim',
        explode(',', (string) env('CORS_ALLOWED_ORIGINS', 'http://localhost:4200'))
    ))),
    'monetization' => 'ads',
    'offer' => [
        'trial_days' => (int) env('SHOPLEDGER_TRIAL_DAYS', 0),
        'lifetime_inr' => (int) env('SHOPLEDGER_LIFETIME_INR', 0),
        'upi_id' => (string) env('SHOPLEDGER_UPI_ID', ''),
        'whatsapp' => (string) env('SHOPLEDGER_WHATSAPP', ''),
    ],
];
