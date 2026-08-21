package com.k2iot.turncred.auth;

import com.k2iot.turncred.tenant.Tenant;

public class CurrentTenantHolder {

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    public static void set(Tenant tenant) { CURRENT.set(tenant); }
    public static Tenant get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}
