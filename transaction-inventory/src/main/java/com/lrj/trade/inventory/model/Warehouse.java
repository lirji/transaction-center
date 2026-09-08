package com.lrj.trade.inventory.model;

/** 仓库只读视图，ID字符串可直接供管理界面选择。 */
public record Warehouse(String id,String warehouseNo,String name,long version) {}
