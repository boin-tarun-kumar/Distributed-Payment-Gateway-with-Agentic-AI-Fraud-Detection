package com.paymentgateway.webhook;

import com.paymentgateway.events.FraudAlertEvent;
import com.paymentgateway.events.PaymentEvent;
import org.springframework.stereotype.Component;

@Component
public class WebhookPayloadEncoder {
    public String encode(PaymentEvent event) {
        StringBuilder json = new StringBuilder("{");
        appendString(json, "transactionId", event.transactionId());
        appendString(json, "merchantId", event.merchantId());
        appendString(json, "paymentMethod", event.paymentMethod());
        appendString(json, "cardToken", event.cardToken());
        appendNumber(json, "amount", event.amount() == null ? null : event.amount().toPlainString());
        appendString(json, "currency", event.currency());
        appendString(json, "status", event.status());
        appendString(json, "ipAddress", event.ipAddress());
        appendString(json, "timestamp", event.timestamp() == null ? null : event.timestamp().toString());
        return json.append('}').toString();
    }

    public String encode(FraudAlertEvent event) {
        StringBuilder json = new StringBuilder("{");
        appendString(json, "alertId", event.alertId());
        appendString(json, "transactionId", event.transactionId());
        appendString(json, "merchantId", event.merchantId());
        appendString(json, "cardToken", event.cardToken());
        appendString(json, "decision", event.decision());
        appendString(json, "aiSummary", event.aiSummary());
        appendBoolean(json, "holdSettlement", event.holdSettlement());
        return json.append('}').toString();
    }

    private static void appendString(StringBuilder json, String name, String value) {
        appendPrefix(json, name);
        appendQuoted(json, value);
    }

    private static void appendNumber(StringBuilder json, String name, String value) {
        appendPrefix(json, name);
        json.append(value == null ? "null" : value);
    }

    private static void appendBoolean(StringBuilder json, String name, boolean value) {
        appendPrefix(json, name);
        json.append(value);
    }

    private static void appendPrefix(StringBuilder json, String name) {
        if (json.length() > 1) {
            json.append(',');
        }
        appendQuoted(json, name);
        json.append(':');
    }

    private static void appendQuoted(StringBuilder json, String value) {
        if (value == null) {
            json.append("null");
            return;
        }
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append("\\u");
                        String hex = Integer.toHexString(character);
                        json.append("0".repeat(4 - hex.length())).append(hex);
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }
}
