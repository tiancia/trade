package com.trade.weibo.interfaces.web;

public class WeiboUnauthorizedException extends RuntimeException {
    public WeiboUnauthorizedException(String message) {
        super(message);
    }
}
