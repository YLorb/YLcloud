package com.ylcloud.service;

import com.ylcloud.DTO.SiteSettingUpdateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.SiteSetting;
import com.ylcloud.mapper.SiteSettingMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SiteSettingServiceTest {
    private final SiteSettingMapper mapper = mock(SiteSettingMapper.class);
    private final SiteSettingService service = new SiteSettingService(mapper);

    @Test
    void readsDynamicLongSetting() {
        when(mapper.getByKey(SiteSettingService.UPLOAD_MAX_FILE_SIZE)).thenReturn(setting("1048576"));

        assertEquals(1048576L,service.getLong(SiteSettingService.UPLOAD_MAX_FILE_SIZE,2048L));
    }

    @Test
    void invalidLongSettingUsesFallback() {
        when(mapper.getByKey(SiteSettingService.UPLOAD_MAX_FILE_SIZE)).thenReturn(setting("not-a-number"));

        assertEquals(2048L,service.getLong(SiteSettingService.UPLOAD_MAX_FILE_SIZE,2048L));
    }

    @Test
    void readsDisabledBooleanSetting() {
        when(mapper.getByKey(SiteSettingService.LLM_ENABLED)).thenReturn(setting("false"));

        assertFalse(service.getBoolean(SiteSettingService.LLM_ENABLED,true));
    }

    @Test
    void rejectsInvalidAdminUrl() {
        SiteSetting setting = editableSetting("site.publicUrl","string");
        when(mapper.getByKey("site.publicUrl")).thenReturn(setting);

        assertThrows(BaseException.class,() -> service.updateBatch(update("site.publicUrl","not-a-url")));
        verify(mapper,never()).updateValue("site.publicUrl","not-a-url");
    }

    @Test
    void rejectsZeroUploadLimit() {
        SiteSetting setting = editableSetting(SiteSettingService.UPLOAD_MAX_FILE_SIZE,"number");
        when(mapper.getByKey(SiteSettingService.UPLOAD_MAX_FILE_SIZE)).thenReturn(setting);

        assertThrows(BaseException.class,() -> service.updateBatch(update(SiteSettingService.UPLOAD_MAX_FILE_SIZE,"0")));
    }

    @Test
    void normalizesBooleanAdminValue() {
        SiteSetting setting = editableSetting(SiteSettingService.LLM_ENABLED,"boolean");
        when(mapper.getByKey(SiteSettingService.LLM_ENABLED)).thenReturn(setting);
        when(mapper.updateValue(SiteSettingService.LLM_ENABLED,"false")).thenReturn(1);

        service.updateBatch(update(SiteSettingService.LLM_ENABLED,"NO"));

        verify(mapper).updateValue(SiteSettingService.LLM_ENABLED,"false");
    }

    private SiteSetting setting(String value) {
        SiteSetting setting = new SiteSetting();
        setting.setSettingValue(value);
        return setting;
    }

    private SiteSetting editableSetting(String key, String valueType) {
        SiteSetting setting = new SiteSetting();
        setting.setSettingKey(key);
        setting.setValueType(valueType);
        setting.setEditable(1);
        setting.setSecret(0);
        return setting;
    }

    private SiteSettingUpdateDTO update(String key, String value) {
        SiteSettingUpdateDTO.Item item = new SiteSettingUpdateDTO.Item();
        item.setKey(key);
        item.setValue(value);
        SiteSettingUpdateDTO dto = new SiteSettingUpdateDTO();
        dto.setSettings(List.of(item));
        return dto;
    }
}
