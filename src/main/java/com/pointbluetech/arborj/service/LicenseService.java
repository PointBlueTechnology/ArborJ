package com.pointbluetech.arborj.service;

/**
 * License service interface for future Paddle integration.
 * One-time activation, perpetual, offline-capable.
 * Currently always returns licensed.
 */
public class LicenseService {

    public boolean isLicensed() {
        // TODO: Integrate Paddle SDK for activation check
        return true;
    }

    public boolean activate(String licenseKey) {
        // TODO: Paddle activation
        return true;
    }

    public String getLicenseStatus() {
        return "Licensed";
    }
}
