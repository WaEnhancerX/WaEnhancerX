package com.waenhancer.xposed.features.privacy;

/**
 * A manual read allowance only exempts an actual read stanza. It must never
 * bypass delivery hiding or Ghost Mode (both expressed by hideDelivery=true).
 * Status receipts are handled separately and are not read exceptions.
 */
final class ExplicitReadReceiptPolicy {
    private ExplicitReadReceiptPolicy() {}

    static boolean maySend(String type, String recipient, String messageId,
                           boolean hideDelivery, RecipientReceiptAllowance grants,
                           long monotonicNowMillis) {
        return !hideDelivery && "read".equals(type) && grants != null
                && grants.isAllowed(recipient, messageId, monotonicNowMillis);
    }

    static final String JOB_GRANT_KEY = "waex_explicit_chat_receipt_grant";

    /** Immutable action-time scope, never inferred from arbitrary fields on a host job. */
    private static final class JobGrant {
        final String recipient;
        final String[] ids;
        JobGrant(String recipient, java.util.Set<String> ids) {
            this.recipient = recipient;
            this.ids = ids.toArray(new String[0]);
        }
    }

    static Object captureJobGrant(String recipient, Object[] authorizedArguments) {
        String exact = ReceiptJidPolicy.canonical(recipient);
        if (exact == null || "status@broadcast".equals(exact) || authorizedArguments == null) return null;
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (Object argument : authorizedArguments) {
            if (!(argument instanceof String[])) continue;
            for (String id : (String[]) argument) {
                if (id == null || id.isEmpty() || id.length() > 1024 || id.indexOf('\0') >= 0) continue;
                ids.add(id);
                if (ids.size() > 4096) return null;
            }
        }
        return ids.isEmpty() ? null : new JobGrant(exact, ids);
    }

    static boolean refreshJobGrant(Object token, String serializedRecipient, String policyTarget,
                                   boolean hideDelivery, RecipientReceiptAllowance grants,
                                   long monotonicNowMillis) {
        if (hideDelivery || grants == null || !(token instanceof JobGrant)) return false;
        JobGrant grant = (JobGrant) token;
        // A participant or a changed serialized recipient cannot enlarge the original action.
        if (!grant.recipient.equals(ReceiptJidPolicy.canonical(serializedRecipient))
                || !grant.recipient.equals(ReceiptJidPolicy.canonical(policyTarget))) return false;
        for (String id : grant.ids) grants.allow(grant.recipient, id, monotonicNowMillis);
        return true;
    }
}
