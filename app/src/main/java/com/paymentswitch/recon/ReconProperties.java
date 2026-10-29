package com.paymentswitch.recon;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param zone time zone that defines a settlement file's business day (which of our transactions
 *             the supplier should have billed in it)
 */
@ConfigurationProperties("payment-switch.recon")
record ReconProperties(@DefaultValue("UTC") ZoneId zone) {
}
