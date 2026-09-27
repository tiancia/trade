package com.trade.weibo;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class FieldPathMapper {

    // ==================== 需要你实现的部分 ====================

    public static Object get(Object root, String path, Object defaultValue) {
        // TODO: 在这里实现
        throw new UnsupportedOperationException("待实现");
    }

    // ==================== 以下是自测用例，不要修改 ====================

    private static int passed;
    private static int failed;

    static class Address {
        private String city;

        Address(String city) {
            this.city = city;
        }
    }

    static class Person {
        private String name;

        Person(String name) {
            this.name = name;
        }
    }

    static class Buyer extends Person {
        private Address address;
        private int age;

        Buyer(String name, Address address, int age) {
            super(name);
            this.address = address;
            this.age = age;
        }
    }

    static class Item {
        private String sku;
        private BigDecimal price;

        Item(String sku, String price) {
            this.sku = sku;
            this.price = new BigDecimal(price);
        }
    }

    static class Order {
        private String orderNo;
        private Buyer buyer;
        private Buyer boss;
        private List<Item> items;
        private String[] codes;
        private int[][] matrix;
        private Map<String, Object> extra;
        private String remark;
    }
    private static Order fixture() {
        Order o = new Order();
        o.orderNo = "NO-1";
        o.buyer = new Buyer("张三", new Address("太原"), 28);
        o.boss = null;
        o.items = Arrays.asList(new Item("S1", "1.00"), new Item("S2", "2.50"));
        o.codes = new String[]{"C0", "C1"};
        o.matrix = new int[][]{{10, 11}, {20, 21}};
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("deep", "D");
        o.extra = new LinkedHashMap<>();
        o.extra.put("channel", "APP");
        o.extra.put("nested", nested);
        o.extra.put("amountText", "12.34");
        o.extra.put("nullValue", null);
        o.remark = null;
        return o;
    }

    private static void check(String name, Object root, String path, Object def, Object expect) {
        Object actual;
        try {
            actual = get(root, path, def);
        } catch (RuntimeException e) {
            failed++;
            System.out.println("  FAIL " + name + "  期望 " + show(expect) + "，实际抛了 " + e);
            return;
        }
        if (Objects.equals(actual, expect)) {
            passed++;
            System.out.println("  PASS " + name + "  -> " + show(actual));
        } else {
            failed++;
            System.out.println("  FAIL " + name + "  期望 " + show(expect) + "，实际 " + show(actual));
        }
    }

    private static void checkThrows(String name, Object root, String path) {
        try {
            Object actual = get(root, path, "DEF");
            failed++;
            System.out.println("  FAIL " + name + "  期望抛 IllegalArgumentException，实际返回 " + show(actual));
        } catch (IllegalArgumentException e) {
            passed++;
            System.out.println("  PASS " + name + "  -> 正确抛出 IllegalArgumentException");
        } catch (RuntimeException e) {
            failed++;
            System.out.println("  FAIL " + name + "  期望 IllegalArgumentException，实际 " + e.getClass().getSimpleName());
        }
    }
    private static String show(Object v) {
        return v == null ? "null" : (v + "(" + v.getClass().getSimpleName() + ")");
    }

    public static void main(String[] args) {
        Order o = fixture();
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("order", o);

        System.out.println("---------- 一、正常取值 ----------");
        check("用例1  一级字段            orderNo", o, "orderNo", "DEF", "NO-1");
        check("用例2  二级字段            buyer.name", o, "buyer.name", "DEF", "张三");
        check("用例3  三级字段            buyer.address.city", o, "buyer.address.city", "DEF", "太原");
        check("用例4  父类字段            buyer.name(声明在 Person)", o, "buyer.name", "DEF", "张三");
        check("用例5  List 下标           items[1].sku", o, "items[1].sku", "DEF", "S2");
        check("用例6  BigDecimal 字段     items[1].price", o, "items[1].price", "DEF", new BigDecimal("2.50"));
        check("用例7  数组下标            codes[0]", o, "codes[0]", "DEF", "C0");
        check("用例8  连续下标            matrix[1][0]", o, "matrix[1][0]", "DEF", 20);
        check("用例9  Map 取值            extra.channel", o, "extra.channel", "DEF", "APP");
        check("用例10 Map 嵌 Map          extra.nested.deep", o, "extra.nested.deep", "DEF", "D");
        check("用例11 基本类型装箱        buyer.age", o, "buyer.age", "DEF", 28);
        check("用例12 Map 作为根          order.items[0].sku", wrapper, "order.items[0].sku", "DEF", "S1");
        System.out.println("---------- 二、数据侧缺失，必须返回 defaultValue（不许抛异常） ----------");
        check("用例13 root 为 null", null, "buyer.name", "DEF", "DEF");
        check("用例14 中间某级为 null     boss.name", o, "boss.name", "DEF", "DEF");
        check("用例15 字段不存在          buyer.phone", o, "buyer.phone", "DEF", "DEF");
        check("用例16 中间字段不存在      buyer.xx.city", o, "buyer.xx.city", "DEF", "DEF");
        check("用例17 List 下标越界       items[9].sku", o, "items[9].sku", "DEF", "DEF");
        check("用例18 数组下标越界        codes[9]", o, "codes[9]", "DEF", "DEF");
        check("用例19 最终值为 null       remark", o, "remark", "DEF", "DEF");
        check("用例20 Map 里的值为 null   extra.nullValue", o, "extra.nullValue", "DEF", "DEF");
        check("用例21 对非容器取下标      orderNo[0]", o, "orderNo[0]", "DEF", "DEF");

        System.out.println("---------- 三、配置侧写错，必须抛 IllegalArgumentException ----------");
        System.out.println("  （注意：这一组只列了最基本的几种，语法非法还有哪些形态请自己界定）");
        checkThrows("用例22 path 为 null", o, null);
        checkThrows("用例23 path 为空串", o, "");
        checkThrows("用例24 path 为空白", o, "   ");
        checkThrows("用例25 连续点        buyer..name", o, "buyer..name");

        System.out.println();
        System.out.println("========== 通过 " + passed + " 项，失败 " + failed + " 项 ==========");
        if (failed == 0) {
            System.out.println("  全部用例通过");
        }
    }
}
