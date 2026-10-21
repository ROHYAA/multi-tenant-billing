<?php

return [
    'secret' => env('JWT_SECRET', ''),
    'expiration_ms' => (int) env('JWT_EXPIRATION', 900000),
    'refresh_expiration_ms' => (int) env('JWT_REFRESH_EXPIRATION', 604800000),
    'issuer' => env('JWT_ISSUER', 'shopledger'),
    'audience' => env('JWT_AUDIENCE', 'shopledger.client'),
    'cookie_secure' => filter_var(env('APP_COOKIE_SECURE', false), FILTER_VALIDATE_BOOLEAN),
];
