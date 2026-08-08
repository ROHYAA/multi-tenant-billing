package com.mtbs.business;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.business.customer.dto.CreateCustomerRequest;
import com.mtbs.business.customer.dto.CustomerResponse;
import com.mtbs.business.customer.dto.UpdateCustomerRequest;
import com.mtbs.business.customer.entity.Customer;
import com.mtbs.business.customer.repository.CustomerRepository;
import com.mtbs.business.customer.service.CustomerService;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.support.TestSchemaHelper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
@DisplayName("CustomerService Integration Tests")
class CustomerServiceTest {

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TestSchemaHelper testSchemaHelper;

    private String currentSchema;

    @BeforeEach
    void setUp() {
        currentSchema = testSchemaHelper.createFreshSchema();
        TenantContext.setTenantId(1L);
        TenantContext.setCurrentSchema(currentSchema);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        testSchemaHelper.dropSchema(currentSchema);
    }

    @Nested
    @DisplayName("create")
    class CreateCustomerTests {

        @Test
        @DisplayName("create saves and returns response")
        void create_savesAndReturnsResponse() {
            CreateCustomerRequest request = CreateCustomerRequest.builder()
                .name("Test Customer")
                .email("customer@test.com")
                .phone("+91-9876543210")
                .address("123 Test Street")
                .build();

            CustomerResponse response = customerService.create(request);

            assertNotNull(response);
            assertNotNull(response.getId());
            assertEquals("Test Customer", response.getName());
            assertEquals("customer@test.com", response.getEmail());
        }

        @Test
        @DisplayName("create duplicate email throws exception")
        void create_duplicateEmail_throwsException() {
            CreateCustomerRequest request = CreateCustomerRequest.builder()
                .name("Test Customer")
                .email("duplicate@test.com")
                .build();

            customerService.create(request);

            assertThrows(com.mtbs.shared.exception.ResourceException.class, () ->
                customerService.create(request)
            );
        }
    }

    @Nested
    @DisplayName("update")
    class UpdateCustomerTests {

        @Test
        @DisplayName("update changes name and phone")
        void update_changesNameAndPhone() {
            Customer customer = customerRepository.save(Customer.builder()
                .name("Original Name")
                .email("test@test.com")
                .phone("+91-0000000000")
                .build());

            UpdateCustomerRequest request = UpdateCustomerRequest.builder()
                .name("Updated Name")
                .phone("+91-9999999999")
                .build();

            CustomerResponse response = customerService.update(customer.getId(), request);

            assertEquals("Updated Name", response.getName());
            assertEquals("+91-9999999999", response.getPhone());
        }

        @Test
        @DisplayName("update not found throws exception")
        void update_notFound_throwsException() {
            UpdateCustomerRequest request = UpdateCustomerRequest.builder()
                .name("New Name")
                .build();

            assertThrows(com.mtbs.shared.exception.ResourceException.class, () ->
                customerService.update(99999L, request)
            );
        }
    }

    @Nested
    @DisplayName("delete")
    class DeleteCustomerTests {

        @Test
        @DisplayName("delete succeeds when no invoices")
        void delete_noInvoices_succeeds() {
            Customer customer = customerRepository.save(Customer.builder()
                .name("Test Customer")
                .email("test@test.com")
                .build());

            assertDoesNotThrow(() ->
                customerService.delete(customer.getId())
            );
        }
    }

    @Nested
    @DisplayName("list")
    class ListCustomersTests {

        @Test
        @DisplayName("list returns paged results")
        void list_returnsPagedResults() {
            for (int i = 0; i < 5; i++) {
                customerRepository.save(Customer.builder()
                    .name("Customer " + i)
                    .email("customer" + i + "@test.com")
                    .build());
            }

            var page = customerService.list(null, org.springframework.data.domain.PageRequest.of(0, 10));

            assertNotNull(page);
            assertEquals(5, page.getTotalElements());
        }

        @Test
        @DisplayName("list returns empty when no customers")
        void list_empty_returnsEmpty() {
            var page = customerService.list(null, org.springframework.data.domain.PageRequest.of(0, 10));

            assertNotNull(page);
            assertEquals(0, page.getTotalElements());
        }
    }

    @Nested
    @DisplayName("getById")
    class GetCustomerByIdTests {

        @Test
        @DisplayName("getById returns customer")
        void getById_returnsCustomer() {
            Customer customer = customerRepository.save(Customer.builder()
                .name("Test Customer")
                .email("test@test.com")
                .build());

            CustomerResponse response = customerService.getById(customer.getId());

            assertNotNull(response);
            assertEquals(customer.getId(), response.getId());
        }

        @Test
        @DisplayName("getById not found throws exception")
        void getById_notFound_throwsException() {
            assertThrows(com.mtbs.shared.exception.ResourceException.class, () ->
                customerService.getById(99999L)
            );
        }
    }
}