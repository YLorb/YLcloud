package com.ylcloud.controller;

import com.ylcloud.DTO.SiteSettingUpdateDTO;
import com.ylcloud.service.AdminPermissionService;
import com.ylcloud.service.SiteSettingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdminSettingControllerWebhookPolicyTest {
    @Test
    void privateWebhookPolicyRequiresDeploymentOwnerInAdditionToAdmin() {
        SiteSettingService settings = mock(SiteSettingService.class);
        AdminPermissionService permissions = mock(AdminPermissionService.class);
        AdminSettingController controller = new AdminSettingController(settings,permissions);
        SiteSettingUpdateDTO.Item item = new SiteSettingUpdateDTO.Item();
        item.setKey("webhook.allowPrivateTargets");
        item.setValue("true");
        SiteSettingUpdateDTO dto = new SiteSettingUpdateDTO();
        dto.setSettings(List.of(item));

        controller.update(dto);

        verify(permissions).requireAdmin();
        verify(permissions).requireDeploymentOwner();
        verify(settings).updateBatch(dto);
    }
}
