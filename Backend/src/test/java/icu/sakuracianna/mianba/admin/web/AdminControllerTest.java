package icu.sakuracianna.mianba.admin.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import icu.sakuracianna.mianba.admin.service.AdminService;
import icu.sakuracianna.mianba.identity.security.AuthenticatedUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminControllerTest {

    @Test
    void writeEndpointsForwardIdempotencyKeysAndAuditReasons() {
        AdminService service = mock(AdminService.class);
        AdminController controller = new AdminController(service);
        UUID adminId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(
                adminId, "admin@example.com", "admin", UUID.randomUUID(), 1);

        controller.updateRefund(refundId, "refund-key",
                new AdminController.RefundUpdateCommand("resolved", "线下退款已完成"), principal);
        controller.updateStatus("user@example.com", "status-key",
                new AdminController.StatusCommand(false, "manual_disable"), principal);
        controller.updateRole("user@example.com", "role-key",
                new AdminController.RoleCommand("admin", "grant_admin"), principal);
        controller.updateSystemConfig("registration_open", "config-key",
                new AdminController.ConfigCommand(false), principal);

        verify(service).updateRefund(adminId, refundId,
                new AdminService.RefundUpdateCommand("resolved", "线下退款已完成"), "refund-key");
        verify(service).updateStatus(
                adminId, "user@example.com", false, "manual_disable", "status-key");
        verify(service).updateRole(
                adminId, "user@example.com", "admin", "grant_admin", "role-key");
        verify(service).updateSystemConfig(adminId, "registration_open", false, "config-key");
    }

    @Test
    void providerUpdateForwardsIdempotencyKey() {
        AdminService service = mock(AdminService.class);
        ProviderController controller = new ProviderController(service);
        UUID adminId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(
                adminId, "admin@example.com", "admin", UUID.randomUUID(), 1);

        controller.update(providerId, "provider-key", new ProviderController.ProviderCommand(false), principal);

        verify(service).updateProvider(adminId, providerId, false, "provider-key");
    }
}
