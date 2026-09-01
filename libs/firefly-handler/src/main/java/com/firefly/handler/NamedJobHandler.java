package com.firefly.handler;

/** Spring-friendly handler contract that carries its registration name. */
public interface NamedJobHandler extends JobHandler {
    String handlerName();
}
