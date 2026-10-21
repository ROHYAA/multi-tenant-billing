<?php

namespace App\Migration;

use Carbon\Carbon;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\File;
use RuntimeException;

class DataMigrator
{
    private const TENANT_TABLES = [
        'roles', 'users', 'refresh_tokens', 'role_permissions',
        'customers', 'products', 'attachments', 'bills', 'bill_items', 'payments',
        'shop_settings', 'number_series', 'outbox_events',
    ];

    private const WITH_SOURCE_ID = [
        'roles', 'users', 'customers', 'products', 'attachments',
        'bills', 'bill_items', 'payments', 'shop_settings', 'number_series',
    ];

    public function __construct(
        private readonly SnapshotReader $source,
        private readonly IdMap $ids = new IdMap,
        private readonly ReconciliationReport $report = new ReconciliationReport,
    ) {}

    public function report(): ReconciliationReport
    {
        return $this->report;
    }

    /**
     * @param  array{dry_run: bool, shop?: ?string, include_admins: bool, allow_existing: bool, copy_files: bool, attachments_path?: ?string}  $options
     */
    public function run(array $options): ReconciliationReport
    {
        $dry = (bool) $options['dry_run'];
        $this->report->payload['dry_run'] = $dry;
        $this->report->payload['source_driver'] = $this->source->driver();

        if (! $dry && ! ($options['allow_existing'] ?? false) && DB::table('shops')->count() > 0) {
            throw new RuntimeException('Target already has shops. Re-run with --allow-existing for idempotent upsert, or use an empty target database.');
        }

        $shops = $this->source->publicRows('shops');
        $filter = $options['shop'] ?? null;
        if ($filter) {
            $shops = array_values(array_filter($shops, function ($shop) use ($filter) {
                $row = $this->arr($shop);

                return (string) $row['id'] === (string) $filter
                    || ($row['slug'] ?? '') === $filter
                    || ($row['schema_name'] ?? '') === $filter;
            }));
        }

        $permMap = $this->mapPermissions($dry);
        $templateMap = $this->mapBillTemplates($dry);

        foreach ($shops as $shopObj) {
            $shop = $this->arr($shopObj);
            $this->copyShop($shop, $dry, $permMap, $templateMap, $options);
        }

        $this->copyAuditLogs($dry, $shops);
        if ($options['include_admins'] ?? false) {
            $this->copyAdmins($dry);
        } else {
            $this->report->skip('platform_admins skipped (pass --include-admins after rotating default passwords)');
        }

        $this->report->payload['skipped'][] = 'QRTZ_* not copied';
        $this->report->payload['skipped'][] = 'plans / subscriptions / Razorpay SaaS tables not copied';
        $this->report->payload['skipped'][] = 'outbox_events payload entity ids not rewritten';

        return $this->report;
    }

    /** @param array<int,int> $permMap @param array<int,int> $templateMap */
    private function copyShop(array $shop, bool $dry, array $permMap, array $templateMap, array $options): void
    {
        $shopId = (int) $shop['id'];
        $schema = (string) ($shop['schema_name'] ?? '');
        if ($schema === '') {
            $this->report->addDiscrepancy("Shop {$shopId} has empty schema_name");

            return;
        }

        $shopStats = [
            'shop_id' => $shopId,
            'schema_name' => $schema,
            'slug' => $shop['slug'] ?? null,
            'counts' => [],
            'money' => [],
        ];

        $this->ids->put('shops', 0, $shopId, $shopId);
        if (! $dry) {
            $this->upsertShop($shop);
        }

        $roles = $this->source->tenantRows($schema, 'roles');
        foreach ($roles as $obj) {
            $row = $this->arr($obj);
            $newId = $this->upsertTenant('roles', $shopId, $row, [
                'name' => $row['name'],
            ], $dry);
            $this->ids->put('roles', $shopId, (int) $row['id'], $newId);
        }

        $users = $this->source->tenantRows($schema, 'users');
        foreach ($users as $obj) {
            $row = $this->arr($obj);
            $roleId = isset($row['role_id']) ? $this->ids->get('roles', $shopId, (int) $row['role_id']) : null;
            $newId = $this->upsertTenant('users', $shopId, $row, [
                'name' => $row['name'],
                'email' => $row['email'],
                'password' => $row['password'],
                'role_id' => $roleId,
                'status' => $row['status'] ?? 'ACTIVE',
                'token_version' => $row['token_version'] ?? 1,
                'is_first_login' => $this->bool($row['is_first_login'] ?? true),
            ], $dry);
            $this->ids->put('users', $shopId, (int) $row['id'], $newId);
        }

        foreach ($this->source->tenantRows($schema, 'refresh_tokens') as $obj) {
            $row = $this->arr($obj);
            $userId = $this->ids->get('users', $shopId, (int) $row['user_id']);
            if ($userId === null) {
                $this->report->addDiscrepancy("Shop {$shopId} refresh_token {$row['id']} orphan user_id {$row['user_id']}");
                continue;
            }
            $newId = $this->upsertTenant('refresh_tokens', $shopId, $row, [
                'token' => $row['token'],
                'user_id' => $userId,
                'expiry_date' => $this->utc($row['expiry_date'] ?? null),
                'revoked' => $this->bool($row['revoked'] ?? false),
            ], $dry, sourceKey: 'id', extraUnique: ['token' => $row['token']]);
            $this->ids->put('refresh_tokens', $shopId, (int) $row['id'], $newId);
        }

        foreach ($this->source->tenantRows($schema, 'role_permissions') as $obj) {
            $row = $this->arr($obj);
            $roleId = $this->ids->get('roles', $shopId, (int) $row['role_id']);
            $permId = $permMap[(int) $row['permission_id']] ?? null;
            if ($roleId === null || $permId === null) {
                $this->report->addDiscrepancy("Shop {$shopId} role_permission {$row['id']} missing role or permission");
                continue;
            }
            $newId = $this->upsertTenant('role_permissions', $shopId, $row, [
                'role_id' => $roleId,
                'permission_id' => $permId,
            ], $dry, extraUnique: ['role_id' => $roleId, 'permission_id' => $permId]);
            $this->ids->put('role_permissions', $shopId, (int) $row['id'], $newId);
        }

        foreach ($this->source->tenantRows($schema, 'customers') as $obj) {
            $row = $this->arr($obj);
            $newId = $this->upsertTenant('customers', $shopId, $row, [
                'name' => $row['name'],
                'email' => $row['email'] ?? null,
                'phone' => $row['phone'] ?? null,
                'address' => $row['address'] ?? null,
                'gstin' => $row['gstin'] ?? null,
                'is_walkin' => $this->bool($row['is_walkin'] ?? false),
            ], $dry);
            $this->ids->put('customers', $shopId, (int) $row['id'], $newId);
        }

        foreach ($this->source->tenantRows($schema, 'products') as $obj) {
            $row = $this->arr($obj);
            $newId = $this->upsertTenant('products', $shopId, $row, [
                'name' => $row['name'],
                'description' => $row['description'] ?? null,
                'price' => $this->money($row['price'] ?? 0),
                'tax_percentage' => $this->money($row['tax_percentage'] ?? 0),
                'hsn_sac_code' => $row['hsn_sac_code'] ?? null,
                'unit' => $row['unit'] ?? null,
                'is_active' => $this->bool($row['is_active'] ?? true),
            ], $dry);
            $this->ids->put('products', $shopId, (int) $row['id'], $newId);
        }

        foreach ($this->source->tenantRows($schema, 'attachments') as $obj) {
            $row = $this->arr($obj);
            $oldKey = (string) ($row['storage_key'] ?? '');
            $newKey = 'shops/'.$shopId.'/'.($row['purpose'] ?? 'file').'/'.$row['id'].'_'.basename(str_replace('\\', '/', $oldKey));
            $newId = $this->upsertTenant('attachments', $shopId, $row, [
                'purpose' => $row['purpose'],
                'file_name' => $row['file_name'],
                'content_type' => $row['content_type'],
                'size_bytes' => $row['size_bytes'],
                'storage_key' => $newKey,
            ], $dry);
            $this->ids->put('attachments', $shopId, (int) $row['id'], $newId);
            $this->report->payload['file_copies'][] = [
                'shop_id' => $shopId,
                'from' => $oldKey,
                'to' => $newKey,
                'copied' => false,
            ];
            if (! $dry && ($options['copy_files'] ?? false) && ($options['attachments_path'] ?? null)) {
                $this->copyFile((string) $options['attachments_path'], $oldKey, $newKey);
            }
        }

        foreach ($this->source->tenantRows($schema, 'bills') as $obj) {
            $row = $this->arr($obj);
            $customerId = $this->ids->get('customers', $shopId, (int) $row['customer_id']);
            if ($customerId === null) {
                $this->report->addDiscrepancy("Shop {$shopId} bill {$row['id']} orphan customer_id {$row['customer_id']}");
                continue;
            }
            $newId = $this->upsertTenant('bills', $shopId, $row, [
                'invoice_number' => $row['invoice_number'],
                'customer_id' => $customerId,
                'status' => $row['status'] ?? 'DRAFT',
                'subtotal' => $this->money($row['subtotal'] ?? 0),
                'tax_amount' => $this->money($row['tax_amount'] ?? 0),
                'discount_amount' => $this->money($row['discount_amount'] ?? 0),
                'total_amount' => $this->money($row['total_amount'] ?? 0),
                'currency' => $row['currency'] ?? 'INR',
                'notes' => $row['notes'] ?? null,
                'due_date' => $this->utc($row['due_date'] ?? null),
                'paid_at' => $this->utc($row['paid_at'] ?? null),
                'pdf_url' => $row['pdf_url'] ?? null,
            ], $dry);
            $this->ids->put('bills', $shopId, (int) $row['id'], $newId);
        }

        foreach ($this->source->tenantRows($schema, 'bill_items') as $obj) {
            $row = $this->arr($obj);
            $invoiceSrc = $row['invoice_id'] ?? $row['bill_id'] ?? null;
            $billId = $invoiceSrc !== null ? $this->ids->get('bills', $shopId, (int) $invoiceSrc) : null;
            if ($billId === null) {
                $this->report->addDiscrepancy("Shop {$shopId} bill_item {$row['id']} orphan invoice_id {$invoiceSrc}");
                continue;
            }
            $productId = isset($row['product_id']) && $row['product_id'] !== null
                ? $this->ids->get('products', $shopId, (int) $row['product_id'])
                : null;
            $newId = $this->upsertTenant('bill_items', $shopId, $row, [
                'bill_id' => $billId,
                'product_id' => $productId,
                'description' => $row['description'],
                'quantity' => $this->money($row['quantity'] ?? 1),
                'unit_price' => $this->money($row['unit_price'] ?? 0),
                'tax_percentage' => $this->money($row['tax_percentage'] ?? 0),
                'tax_amount' => $this->money($row['tax_amount'] ?? 0),
                'total' => $this->money($row['total'] ?? 0),
            ], $dry);
            $this->ids->put('bill_items', $shopId, (int) $row['id'], $newId);
        }

        foreach ($this->source->tenantRows($schema, 'payments') as $obj) {
            $row = $this->arr($obj);
            $invoiceSrc = $row['invoice_id'] ?? $row['bill_id'] ?? null;
            $billId = $invoiceSrc !== null ? $this->ids->get('bills', $shopId, (int) $invoiceSrc) : null;
            if ($billId === null) {
                $this->report->addDiscrepancy("Shop {$shopId} payment {$row['id']} orphan invoice_id {$invoiceSrc}");
                continue;
            }
            $newId = $this->upsertTenant('payments', $shopId, $row, [
                'bill_id' => $billId,
                'amount' => $this->money($row['amount']),
                'currency' => $row['currency'] ?? 'INR',
                'method' => $row['method'],
                'status' => $row['status'] ?? 'CONFIRMED',
                'payment_group_id' => $row['payment_group_id'] ?? null,
                'notes' => $row['notes'] ?? null,
                'paid_at' => $this->utc($row['paid_at'] ?? now()),
            ], $dry);
            $this->ids->put('payments', $shopId, (int) $row['id'], $newId);
        }

        foreach ($this->source->tenantRows($schema, 'shop_settings') as $obj) {
            $row = $this->arr($obj);
            $tplSrc = (int) ($row['bill_template_id'] ?? 0);
            $tplId = $templateMap[$tplSrc] ?? DB::table('bill_templates')->orderBy('id')->value('id');
            if (! $tplId) {
                $this->report->addDiscrepancy("Shop {$shopId} shop_settings missing bill_template");
                continue;
            }
            $logo = isset($row['logo_attachment_id']) && $row['logo_attachment_id']
                ? $this->ids->get('attachments', $shopId, (int) $row['logo_attachment_id'])
                : null;
            $sig = isset($row['signature_attachment_id']) && $row['signature_attachment_id']
                ? $this->ids->get('attachments', $shopId, (int) $row['signature_attachment_id'])
                : null;
            $newId = $this->upsertTenant('shop_settings', $shopId, $row, [
                'business_name' => $row['business_name'] ?? null,
                'logo_attachment_id' => $logo,
                'business_type' => $row['business_type'] ?? null,
                'address' => $row['address'] ?? null,
                'city' => $row['city'] ?? null,
                'state' => $row['state'] ?? null,
                'pincode' => $row['pincode'] ?? null,
                'mobile' => $row['mobile'] ?? null,
                'email' => $row['email'] ?? null,
                'gstin' => $row['gstin'] ?? null,
                'pan' => $row['pan'] ?? null,
                'website' => $row['website'] ?? null,
                'currency' => $row['currency'] ?? 'INR',
                'currency_symbol' => $row['currency_symbol'] ?? '₹',
                'decimal_precision' => $row['decimal_precision'] ?? 2,
                'timezone' => $row['timezone'] ?? 'Asia/Kolkata',
                'language' => $row['language'] ?? 'en-IN',
                'date_format' => $row['date_format'] ?? 'dd/MM/yyyy',
                'paper_size' => $row['paper_size'] ?? 'A4',
                'bill_template_id' => $tplId,
                'show_logo' => $this->bool($row['show_logo'] ?? true),
                'show_gst' => $this->bool($row['show_gst'] ?? true),
                'show_qr_code' => $this->bool($row['show_qr_code'] ?? false),
                'show_customer_address' => $this->bool($row['show_customer_address'] ?? true),
                'show_amount_in_words' => $this->bool($row['show_amount_in_words'] ?? true),
                'show_signature' => $this->bool($row['show_signature'] ?? false),
                'terms_and_conditions' => $row['terms_and_conditions'] ?? null,
                'warranty_text' => $row['warranty_text'] ?? null,
                'footer_message' => $row['footer_message'] ?? null,
                'thermal_width' => $row['thermal_width'] ?? null,
                'margin' => $row['margin'] ?? 5,
                'font_size' => $row['font_size'] ?? 10,
                'upi_id' => $row['upi_id'] ?? null,
                'signature_attachment_id' => $sig,
                'watermark_text' => $row['watermark_text'] ?? null,
                'bank_name' => $row['bank_name'] ?? null,
                'bank_account_no' => $row['bank_account_no'] ?? null,
                'bank_ifsc' => $row['bank_ifsc'] ?? null,
                'bank_branch' => $row['bank_branch'] ?? null,
            ], $dry, extraUnique: ['shop_id' => $shopId]);
            $this->ids->put('shop_settings', $shopId, (int) $row['id'], $newId);
        }

        foreach ($this->source->tenantRows($schema, 'number_series') as $obj) {
            $row = $this->arr($obj);
            $newId = $this->upsertTenant('number_series', $shopId, $row, [
                'series_type' => $row['series_type'],
                'prefix' => $row['prefix'],
                'financial_year_format' => $row['financial_year_format'] ?? 'NONE',
                'starting_number' => $row['starting_number'] ?? 1,
                'current_number' => $row['current_number'] ?? 0,
                'is_active' => $this->bool($row['is_active'] ?? true),
            ], $dry);
            $this->ids->put('number_series', $shopId, (int) $row['id'], $newId);
        }

        foreach ($this->source->tenantRows($schema, 'outbox_events') as $obj) {
            $row = $this->arr($obj);
            $newId = $this->upsertTenant('outbox_events', $shopId, $row, [
                'event_type' => $row['event_type'],
                'aggregate_type' => $row['aggregate_type'] ?? null,
                'aggregate_id' => $row['aggregate_id'] ?? null,
                'payload' => is_string($row['payload'] ?? '') ? $row['payload'] : json_encode($row['payload'] ?? []),
                'event_class' => $row['event_class'] ?? 'legacy',
                'idempotency_key' => $row['idempotency_key'] ?? null,
                'status' => $row['status'] ?? 'PENDING',
                'retry_count' => $row['retry_count'] ?? 0,
                'max_retries' => $row['max_retries'] ?? 5,
                'last_error' => $row['last_error'] ?? null,
                'processed_at' => $this->utc($row['processed_at'] ?? null),
                'locked_until' => $this->utc($row['locked_until'] ?? null),
            ], $dry);
            $this->ids->put('outbox_events', $shopId, (int) $row['id'], $newId);
        }

        $this->reconcileShop($schema, $shopId, $shopStats, $dry);
        $this->report->payload['shops'][] = $shopStats;
    }

    private function reconcileShop(string $schema, int $shopId, array &$stats, bool $dry): void
    {
        foreach (self::TENANT_TABLES as $table) {
            $src = $this->source->tenantCount($schema, $table);
            $dst = $dry ? $src : DB::table($table)->where('shop_id', $shopId)->count();
            $stats['counts'][$table] = ['source' => $src, 'target' => $dst];
            if (! $dry && $src !== $dst) {
                $this->report->addDiscrepancy("Count mismatch {$table} shop={$shopId} source={$src} target={$dst}");
            }
        }

        $srcBills = $this->source->tenantRows($schema, 'bills');
        $srcPay = $this->source->tenantRows($schema, 'payments');
        $billTotal = '0.00';
        $payTotal = '0.00';
        $invoices = [];
        foreach ($srcBills as $obj) {
            $row = $this->arr($obj);
            $billTotal = $this->addMoney($billTotal, $row['total_amount'] ?? 0);
            $inv = (string) ($row['invoice_number'] ?? '');
            $invoices[$inv] = ($invoices[$inv] ?? 0) + 1;
        }
        foreach ($srcPay as $obj) {
            $row = $this->arr($obj);
            if (($row['status'] ?? '') === 'CONFIRMED') {
                $payTotal = $this->addMoney($payTotal, $row['amount'] ?? 0);
            }
        }
        foreach ($invoices as $inv => $n) {
            if ($n > 1) {
                $this->report->addDiscrepancy("Duplicate invoice_number {$inv} in source shop {$shopId}");
            }
        }

        $stats['money']['bills_total'] = $billTotal;
        $stats['money']['confirmed_payments_total'] = $payTotal;
        $stats['money']['outstanding'] = number_format((float) $billTotal - (float) $payTotal, 2, '.', '');

        if (! $dry) {
            $dstBill = (string) DB::table('bills')->where('shop_id', $shopId)->sum('total_amount');
            $dstPay = (string) DB::table('payments')->where('shop_id', $shopId)->where('status', 'CONFIRMED')->sum('amount');
            $stats['money']['target_bills_total'] = $this->money($dstBill);
            $stats['money']['target_confirmed_payments_total'] = $this->money($dstPay);
            if ($this->money($dstBill) !== $billTotal) {
                $this->report->addDiscrepancy("Bill total mismatch shop={$shopId} source={$billTotal} target={$dstBill}");
            }
            if ($this->money($dstPay) !== $payTotal) {
                $this->report->addDiscrepancy("Payment total mismatch shop={$shopId} source={$payTotal} target={$dstPay}");
            }
            $nsSrc = $this->source->tenantRows($schema, 'number_series');
            foreach ($nsSrc as $obj) {
                $row = $this->arr($obj);
                $cur = DB::table('number_series')->where('shop_id', $shopId)->where('source_id', $row['id'])->value('current_number');
                if ((string) $cur !== (string) ($row['current_number'] ?? 0)) {
                    $this->report->addDiscrepancy("number_series.current_number mismatch shop={$shopId} source_id={$row['id']}");
                }
            }
        }
    }

    /** @return array<int,int> */
    private function mapPermissions(bool $dry): array
    {
        $map = [];
        foreach ($this->source->publicRows('permissions') as $obj) {
            $row = $this->arr($obj);
            $existing = DB::table('permissions')->where('name', $row['name'])->value('id');
            if (! $existing && ! $dry) {
                $existing = DB::table('permissions')->insertGetId($this->auditPayload($row) + [
                    'name' => $row['name'],
                    'description' => $row['description'] ?? null,
                ]);
            }
            if ($existing) {
                $map[(int) $row['id']] = (int) $existing;
            } else {
                $map[(int) $row['id']] = (int) $row['id'];
            }
        }

        return $map;
    }

    /** @return array<int,int> */
    private function mapBillTemplates(bool $dry): array
    {
        $map = [];
        foreach ($this->source->publicRows('bill_templates') as $obj) {
            $row = $this->arr($obj);
            $existing = DB::table('bill_templates')->where('code', $row['code'])->value('id');
            if (! $existing && ! $dry) {
                $existing = DB::table('bill_templates')->insertGetId($this->auditPayload($row) + [
                    'code' => $row['code'],
                    'name' => $row['name'],
                    'description' => $row['description'] ?? null,
                    'is_active' => $this->bool($row['is_active'] ?? true),
                ]);
            }
            if ($existing) {
                $map[(int) $row['id']] = (int) $existing;
            }
        }

        return $map;
    }

    private function upsertShop(array $shop): void
    {
        $payload = $this->auditPayload($shop) + [
            'id' => (int) $shop['id'],
            'name' => $shop['name'],
            'schema_name' => $shop['schema_name'] ?? null,
            'status' => $shop['status'] ?? 'PENDING_APPROVAL',
            'owner_email' => $shop['owner_email'] ?? null,
            'slug' => $shop['slug'] ?? null,
            'plan_name' => $shop['plan_name'] ?? null,
            'subscription_expires_at' => $this->utc($shop['subscription_expires_at'] ?? null),
            'expiry_alert_sent_at' => $this->utc($shop['expiry_alert_sent_at'] ?? null),
        ];
        if (DB::table('shops')->where('id', $shop['id'])->exists()) {
            return;
        }
        DB::table('shops')->insert($payload);
    }

    /**
     * @param  array<string, mixed>  $fields
     * @param  array<string, mixed>  $extraUnique
     */
    private function upsertTenant(
        string $table,
        int $shopId,
        array $row,
        array $fields,
        bool $dry,
        string $sourceKey = 'id',
        array $extraUnique = [],
    ): int {
        $sourceId = (int) $row[$sourceKey];
        if ($dry) {
            return $sourceId;
        }

        if (in_array($table, self::WITH_SOURCE_ID, true)) {
            $found = DB::table($table)->where('shop_id', $shopId)->where('source_id', $sourceId)->value('id');
            if ($found) {
                return (int) $found;
            }
        }
        if ($extraUnique !== []) {
            $query = DB::table($table)->where('shop_id', $shopId);
            foreach ($extraUnique as $col => $val) {
                $query->where($col, $val);
            }
            $found = $query->value('id');
            if ($found) {
                return (int) $found;
            }
        }

        $payload = $this->auditPayload($row) + $fields + ['shop_id' => $shopId];
        if (in_array($table, self::WITH_SOURCE_ID, true)) {
            $payload['source_id'] = $sourceId;
        }

        return (int) DB::table($table)->insertGetId($payload);
    }

    private function copyAuditLogs(bool $dry, array $shops): void
    {
        $shopIds = [];
        foreach ($shops as $obj) {
            $shopIds[(int) $this->arr($obj)['id']] = true;
        }
        foreach ($this->source->publicRows('audit_logs') as $obj) {
            $row = $this->arr($obj);
            $tenant = isset($row['context_tenant_id']) ? (int) $row['context_tenant_id'] : 0;
            if ($tenant && ! isset($shopIds[$tenant])) {
                continue;
            }
            if ($dry) {
                continue;
            }
            if (DB::table('audit_logs')->where('id', $row['id'])->exists()) {
                continue;
            }
            DB::table('audit_logs')->insert($this->auditPayload($row) + [
                'id' => $row['id'],
                'who_user_id' => $row['who_user_id'] ?? null,
                'who_user_email' => $row['who_user_email'] ?? null,
                'who_user_name' => $row['who_user_name'] ?? null,
                'who_role' => $row['who_role'] ?? null,
                'what_action' => $row['what_action'],
                'where_entity_type' => $row['where_entity_type'],
                'where_entity_id' => $row['where_entity_id'] ?? null,
                'where_entity_name' => $row['where_entity_name'] ?? null,
                'changes_before' => $this->json($row['changes_before'] ?? null),
                'changes_after' => $this->json($row['changes_after'] ?? null),
                'changes_summary' => $this->json($row['changes_summary'] ?? null),
                'context_tenant_id' => $row['context_tenant_id'] ?? null,
                'context_tenant_name' => $row['context_tenant_name'] ?? null,
                'context_ip_address' => $row['context_ip_address'] ?? null,
                'context_user_agent' => $row['context_user_agent'] ?? null,
                'context_metadata' => $this->json($row['context_metadata'] ?? null),
                'description' => $row['description'] ?? null,
                'module' => $row['module'] ?? null,
                'severity' => $row['severity'] ?? 'INFO',
            ]);
        }
    }

    private function copyAdmins(bool $dry): void
    {
        foreach ($this->source->publicRows('platform_admins') as $obj) {
            $row = $this->arr($obj);
            $email = strtolower((string) $row['email']);
            if ($email === 'admin@platform.com') {
                $this->report->skip('Skipped default admin@platform.com (rotate before --include-admins copy)');
                continue;
            }
            if ($dry) {
                continue;
            }
            if (DB::table('platform_admins')->where('email', $row['email'])->exists()) {
                continue;
            }
            DB::table('platform_admins')->insert([
                'email' => $row['email'],
                'password' => $row['password'],
                'name' => $row['name'],
                'role' => $row['role'] ?? 'SUPER_ADMIN',
                'active' => $this->bool($row['active'] ?? true),
                'created_at' => $this->utc($row['created_at'] ?? now()),
            ]);
        }
    }

    private function copyFile(string $root, string $from, string $to): void
    {
        $src = rtrim($root, '/\\').DIRECTORY_SEPARATOR.ltrim(str_replace('/', DIRECTORY_SEPARATOR, $from), '\\/');
        $dest = storage_path('app/'.$to);
        if (! is_file($src)) {
            $this->report->warn("Attachment missing on disk: {$src}");

            return;
        }
        File::ensureDirectoryExists(dirname($dest));
        File::copy($src, $dest);
        $last = count($this->report->payload['file_copies']) - 1;
        if ($last >= 0) {
            $this->report->payload['file_copies'][$last]['copied'] = true;
        }
    }

    /** @return array<string, mixed> */
    private function auditPayload(array $row): array
    {
        return [
            'deleted' => $this->bool($row['deleted'] ?? false),
            'deleted_at' => $this->utc($row['deleted_at'] ?? null),
            'version' => $row['version'] ?? 0,
            'created_at' => $this->utc($row['created_at'] ?? now()) ?? now(),
            'updated_at' => $this->utc($row['updated_at'] ?? now()) ?? now(),
            'created_by' => is_numeric($row['created_by'] ?? null) ? (int) $row['created_by'] : null,
            'updated_by' => is_numeric($row['updated_by'] ?? null) ? (int) $row['updated_by'] : null,
        ];
    }

    /** @return array<string, mixed> */
    private function arr(object $row): array
    {
        return json_decode(json_encode($row), true) ?: [];
    }

    private function bool(mixed $v): int
    {
        return filter_var($v, FILTER_VALIDATE_BOOLEAN) || $v === 1 || $v === '1' ? 1 : 0;
    }

    private function utc(mixed $v): ?string
    {
        if ($v === null || $v === '') {
            return null;
        }
        try {
            return Carbon::parse((string) $v)->utc()->format('Y-m-d H:i:s');
        } catch (\Throwable) {
            return (string) $v;
        }
    }

    private function money(mixed $v): string
    {
        return number_format((float) $v, 2, '.', '');
    }

    private function addMoney(string $a, mixed $b): string
    {
        return number_format((float) $a + (float) $this->money($b), 2, '.', '');
    }

    private function json(mixed $v): ?string
    {
        if ($v === null || $v === '') {
            return null;
        }
        if (is_array($v) || is_object($v)) {
            return json_encode($v);
        }

        return (string) $v;
    }
}
