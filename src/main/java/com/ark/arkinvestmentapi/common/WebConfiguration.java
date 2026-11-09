package com.ark.arkinvestmentapi.common;

import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

import java.beans.PropertyEditorSupport;
import java.util.UUID;

@ControllerAdvice
public class WebConfiguration {
    @InitBinder
    void strictUuid(WebDataBinder binder) {
        binder.registerCustomEditor(UUID.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String value) {
                setValue(Uuids.parse(value));
            }
        });
    }
}
