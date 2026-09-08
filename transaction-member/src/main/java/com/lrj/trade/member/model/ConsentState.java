package com.lrj.trade.member.model;

/** 某一用途的最近同意动作，撤回时必须匹配此版本。 */
public record ConsentState(String action,String documentVersion) {}
