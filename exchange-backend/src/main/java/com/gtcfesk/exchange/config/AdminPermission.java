package com.gtcfesk.exchange.config;

import java.lang.annotation.*;

/** Explicit menu/action boundary, evaluated before controller side effects. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AdminPermission {
    String menu();
    String action() default "";
}
