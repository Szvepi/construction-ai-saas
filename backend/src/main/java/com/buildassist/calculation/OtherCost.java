package com.buildassist.calculation;

import java.util.Objects;

public record OtherCost(
        String id,
        String workItemId,
        NetMoney directCost) {

    public OtherCost {
        WorkItem.requireId(id);
        WorkItem.requireId(workItemId);
    }
}
