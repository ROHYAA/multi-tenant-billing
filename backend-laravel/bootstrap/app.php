<?php

use App\Exceptions\ApiException;
use App\Http\Middleware\AuthenticateJwt;
use App\Http\Middleware\ClearShopContext;
use App\Http\Middleware\EnsurePermission;
use App\Http\Middleware\EnsureSuperAdmin;
use App\Support\ApiResponse;
use Illuminate\Foundation\Application;
use Illuminate\Foundation\Configuration\Exceptions;
use Illuminate\Foundation\Configuration\Middleware;
use Illuminate\Http\Request;
use Illuminate\Validation\ValidationException;
use Symfony\Component\HttpKernel\Exception\AccessDeniedHttpException;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;
use Symfony\Component\HttpKernel\Exception\TooManyRequestsHttpException;

return Application::configure(basePath: dirname(__DIR__))
    ->withCommands([
        \App\Console\Commands\MigratePostgresDataCommand::class,
    ])
    ->withRouting(
        web: __DIR__.'/../routes/web.php',
        api: __DIR__.'/../routes/api.php',
        commands: __DIR__.'/../routes/console.php',
        health: '/up',
        apiPrefix: 'api/v1',
    )
    ->withMiddleware(function (Middleware $middleware): void {
        $middleware->append(ClearShopContext::class);
        $middleware->alias([
            'auth.jwt' => AuthenticateJwt::class,
            'super.admin' => EnsureSuperAdmin::class,
            'permission' => EnsurePermission::class,
        ]);
        $middleware->validateCsrfTokens(except: ['api/*']);
        $middleware->encryptCookies(except: [
            'access_token',
            'refresh_token',
        ]);
    })
    ->withExceptions(function (Exceptions $exceptions): void {
        $exceptions->shouldRenderJsonWhen(
            fn (Request $request) => $request->is('api/*') || $request->expectsJson()
        );

        $exceptions->render(function (ApiException $e) {
            return ApiResponse::error($e->getMessage(), $e->errorCode, $e->httpStatus);
        });

        $exceptions->render(function (ValidationException $e) {
            $fields = [];
            foreach ($e->errors() as $field => $messages) {
                $fields[$field] = $messages[0] ?? 'Invalid';
            }

            return ApiResponse::error('Validation failed', 'VAL_6001', 400, $fields);
        });

        $exceptions->render(function (AccessDeniedHttpException $e) {
            return ApiResponse::error('Access denied', 'AUTH_1003', 403);
        });

        $exceptions->render(function (NotFoundHttpException $e, Request $request) {
            if ($request->is('api/*')) {
                return ApiResponse::error('Not found', 'RES_4001', 404);
            }

            return null;
        });

        $exceptions->render(function (TooManyRequestsHttpException $e) {
            return ApiResponse::error('Too many failed login attempts. Try again later.', 'AUTH_1009', 429);
        });

        $exceptions->render(function (\Throwable $e, Request $request) {
            if (! $request->is('api/*')) {
                return null;
            }

            return ApiResponse::error(
                config('app.debug') ? $e->getMessage() : 'Internal server error',
                'GEN_9001',
                500
            );
        });
    })->create();
