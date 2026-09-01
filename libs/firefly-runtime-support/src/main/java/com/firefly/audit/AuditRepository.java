package com.firefly.audit;

import java.util.List;

public interface AuditRepository {
    void append(AuditRecord record);

    List<AuditRecord> listRecent(int limit);
}
