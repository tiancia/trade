package com.trade.x.interfaces.web;

public class XUnauthorizedException extends RuntimeException {
    public XUnauthorizedException() { super("X admin token is invalid"); }
}
