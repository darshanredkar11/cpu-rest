package com.cpurest.server;

import com.cpurest.util.RouteMeta;

import java.lang.reflect.Method;

record RouteHandlerBinding(RouteMeta meta, Object controller, Method method) {}
