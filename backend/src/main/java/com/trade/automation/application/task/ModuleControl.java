package com.trade.automation.application.task;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Domain settings stay with their owners; automation only coordinates their lifecycle. */
public record ModuleControl(String id, String name, String description, BooleanSupplier enabled,
                            Consumer<Boolean> changeEnabled, Runnable validateEnable,
                            Supplier<List<String>> notices) {}
