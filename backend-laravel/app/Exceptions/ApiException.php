<?php

namespace App\Exceptions;

use RuntimeException;

class ApiException extends RuntimeException
{
    public function __construct(
        string $message,
        public readonly string $errorCode,
        public readonly int $httpStatus = 400,
    ) {
        parent::__construct($message);
    }

    public static function invalidCredentials(): self
    {
        return new self('Invalid credentials', 'AUTH_1001', 401);
    }

    public static function emailExists(): self
    {
        return new self('Email already exists', 'AUTH_1006', 409);
    }

    public static function accessDenied(string $message = 'Access denied'): self
    {
        return new self($message, 'AUTH_1003', 403);
    }

    public static function tooManyRequests(): self
    {
        return new self('Too many failed login attempts. Try again later.', 'AUTH_1009', 429);
    }

    public static function tokenRevoked(): self
    {
        return new self('Token has been revoked', 'TOKEN_REVOKED', 401);
    }

    public static function shopSuspended(): self
    {
        return new self('Shop is suspended', 'TNT_2004', 403);
    }

    public static function tenantNotFound(): self
    {
        return new self('Tenant not found', 'TNT_2001', 404);
    }

    public static function notPendingApproval(): self
    {
        return new self('Tenant is not pending approval', 'TNT_2009', 409);
    }

    public static function notSuspended(): self
    {
        return new self('Tenant is not suspended', 'TNT_2010', 409);
    }

    public static function alreadyLifetime(): self
    {
        return new self('Shop already has a lifetime plan.', 'TNT_2011', 409);
    }

    public static function resetTokenInvalid(): self
    {
        return new self('Password reset token is invalid', 'AUTH_1007', 400);
    }

    public static function notFound(string $entity, mixed $id = null): self
    {
        $suffix = $id !== null ? " ($id)" : '';

        return new self($entity.' not found'.$suffix, 'RES_4001', 404);
    }

    public static function alreadyExists(string $resource, string $identifier): self
    {
        return new self($resource.': '.$identifier, 'RES_4002', 409);
    }

    public static function resourceAccessDenied(string $detail = 'Access to resource denied'): self
    {
        return new self($detail, 'RES_4003', 403);
    }

    public static function invalid(string $detail): self
    {
        return new self($detail, 'RES_4004', 400);
    }
}
