package com.lsd.etl.itag.service;

import com.google.gson.Gson;
import com.lsd.etl.itag.service.EsService;
import com.lsd.etl.itag.util.SparkETLUtils;
import com.lsd.etl.itag.vo.MemberTag;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;

/**
 * 使用Spark内存计算出每个用户的标签信息，并存入ES中
 * <p>
 * Created by lsd
 * 2020-03-05 19:15
 */
@Component
public class MemberTagETLService {

    private static SparkSession session;
    @Autowired
    private Gson gson;
    @Autowired
    private EsService esService;
    @Autowired
    private SparkETLUtils sparkETLUtils;


//    public static void main(String[] args) {
//        etlAndIndex();
//    }


    public void etlAndIndex() {
        // 查询会员表
        Dataset<Row> member = session.sql(
                "select id as memberId,phone,sex,member_channel as channel,mp_open_id as subOpenId," +
                        " address_default_id as address,date_format(create_time,'yyyy-MM-dd') as regTime" +
                        " from i_member.t_member"
        );
        // 按会员聚合商品+订单表
        // 其中SparkSQL中的 collect_list 相当于MySQL的 group_concat 函数，只是它会把group_concat后的结果封装成[]而不是字符串
        Dataset<Row> order_commodity = session.sql(
                "SELECT o.member_id AS memberId, date_format( max( o.create_time ), 'yyyy-MM-dd' ) AS orderTime," +
                        " count( DISTINCT o.order_id ) AS orderCount, collect_list( DISTINCT oc.commodity_id ) AS favGoods," +
                        " collect_set(sum_by_member.orderMoney)[0] AS orderMoney" +
                        " FROM i_order.t_order AS o" +
                        " LEFT JOIN (" +    //先求出每个会员的消费总额再连接上去每条订单-商品中间表记录得到每个会员购买过的商品ids
                        " SELECT o.member_id, sum( o.pay_price ) AS orderMoney FROM i_order.t_order o GROUP BY o.member_id" +
                        ") sum_by_member ON o.member_id = sum_by_member.member_id" +
                        " LEFT JOIN i_order.t_order_commodity AS oc ON o.order_id = oc.order_id" +
                        " GROUP BY o.member_id"
        );
        // 所有会员的"首单免费"优惠券信息
        Dataset<Row> freeCoupon = session.sql(
                "select member_id as memberId, " +
                        " date_format(create_time,'yyyy-MM-dd') as freeCouponTime" +
                        " from i_marketing.t_coupon_member" +
                        " where coupon_id = 1"
        );
        // 按会员聚合优惠券信息
        Dataset<Row> couponTimes = session.sql(
                "select member_id as memberId," +
                        " collect_list(date_format(create_time,'yyyy-MM-dd')) as couponTimes" +
                        " from i_marketing.t_coupon_member" +
                        " where coupon_id !=1" +
                        " group by member_id"
        );
        // 按会员聚合充值优惠券信息
        Dataset<Row> chargeMoney = session.sql(
                "select cm.member_id as memberId , sum(c.coupon_price/2) as chargeMoney" +
                        " from i_marketing.t_coupon_member as cm" +
                        " left join i_marketing.t_coupon as c" +
                        " on cm.coupon_id = c.id" +
                        " where cm.coupon_channel = 1" +   //coupon_channel = 1即用户购买的
                        " group by cm.member_id"
        );

        // 按会员聚合送餐表的最大送餐超时（时间戳）
        Dataset<Row> overTime = session.sql(
                "select (to_unix_timestamp(max(arrive_time)) - to_unix_timestamp(max(pick_time))) as overTime," +
                        " member_id as memberId" +
                        " from i_operation.t_delivery" +
                        " group by member_id"
        );

        // 查询每个会员最后一条反馈的反馈类型
        Dataset<Row> feedback = session.sql(
                "select fb.feedback_type as feedback,fb.member_id as memberId" +
                        " from i_operation.t_feedback as fb" +
                        " left join (" +
                        "select max(id) as mid,member_id as memberId" +
                        " from i_operation.t_feedback group by member_id" +
                        ") as t on fb.id = t.mid"
        );

        // 以上全部转为临时表，并根据会员id连接为宽表
        member.registerTempTable("member");
        order_commodity.registerTempTable("oc");
        freeCoupon.registerTempTable("freeCoupon");
        couponTimes.registerTempTable("couponTimes");
        chargeMoney.registerTempTable("chargeMoney");
        overTime.registerTempTable("overTime");
        feedback.registerTempTable("feedback");

//        Dataset<Row> result = session.sql(
//                "select m.*,o.orderCount,o.orderTime,o.orderMoney,o.favGoods," +
//                        " fb.freeCouponTime,ct.couponTimes, cm.chargeMoney,ot.overTime,f.feedBack" +
//                        " from member as m" +
//                        " left join oc as o on m.memberId = o.memberId" +
//                        " left join freeCoupon as fb on m.memberId = fb.memberId" +
//                        " left join couponTimes as ct on m.memberId = ct.memberId" +
//                        " left join chargeMoney as cm on m.memberId = cm.memberId" +
//                        " left join overTime as ot on m.memberId = ot.memberId" +
//                        " left join feedback as f on m.memberId = f.memberId"
//        );
        // elasticsearch-hadoop依赖与Springboot的打包插件不兼容
        // https://github.com/elastic/elasticsearch-hadoop/issues/1359
//        JavaEsSparkSQL.saveToEs(result, "tag");   // 直接存入ES中,第二个参数是indexName

        //得到spark计算结果，手动存入es
        String sql = "select m.*,o.orderCount,o.orderTime,o.orderMoney,o.favGoods," +
                " fb.freeCouponTime,ct.couponTimes, cm.chargeMoney,ot.overTime,f.feedBack" +
                " from member as m" +
                " left join oc as o on m.memberId = o.memberId" +
                " left join freeCoupon as fb on m.memberId = fb.memberId" +
                " left join couponTimes as ct on m.memberId = ct.memberId" +
                " left join chargeMoney as cm on m.memberId = cm.memberId" +
                " left join overTime as ot on m.memberId = ot.memberId" +
                " left join feedback as f on m.memberId = f.memberId";
        List<MemberTag> memberTags = sparkETLUtils.execAndCollectAsList(session, sql, MemberTag.class);
//        System.out.println(memberTags);

        esService.saveToEs(memberTags);
    }


}
