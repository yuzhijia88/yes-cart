package org.yes.cart.service.order.impl.handler;

import org.junit.Before;
import org.junit.Test;
import org.yes.cart.constants.ServiceSpringKeys;
import org.yes.cart.domain.entity.CustomerOrder;
import org.yes.cart.domain.entity.CustomerOrderDelivery;
import org.yes.cart.payment.PaymentGateway;
import org.yes.cart.payment.dto.Payment;
import org.yes.cart.payment.impl.TestPaymentGatewayImpl;
import org.yes.cart.payment.service.CustomerOrderPaymentService;
import org.yes.cart.service.domain.CustomerOrderService;
import org.yes.cart.service.order.OrderEventHandler;
import org.yes.cart.service.order.OrderException;
import org.yes.cart.service.order.impl.OrderEventImpl;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;

import static org.junit.Assert.*;

/**
 * User: denispavlov
 * Date: 07/01/2018
 * Time: 19:19
 */
public class ExternalRefundOrderEventHandlerImplTest extends AbstractEventHandlerImplTest {

    private OrderEventHandler pendingHandler;
    private OrderEventHandler allocationHandler;
    private OrderEventHandler packingHandler;
    private OrderEventHandler packedHandler;
    private OrderEventHandler releaseHandler;
    private OrderEventHandler shippedHandler;
    private OrderEventHandler cancelHandler;
    private OrderEventHandler handler;

    private CustomerOrderService orderService;
    private CustomerOrderPaymentService paymentService;

    @Override
    @Before
    public void setUp()  {
        super.setUp();
        pendingHandler = (OrderEventHandler) ctx().getBean("pendingOrderEventHandler");
        allocationHandler = (OrderEventHandler) ctx().getBean("processAllocationOrderEventHandler");
        packingHandler = (OrderEventHandler) ctx().getBean("releaseToPackOrderEventHandler");
        packedHandler = (OrderEventHandler) ctx().getBean("packCompleteOrderEventHandler");
        releaseHandler = (OrderEventHandler) ctx().getBean("releaseToShipmentOrderEventHandler");
        shippedHandler = (OrderEventHandler) ctx().getBean("shipmentCompleteOrderEventHandler");
        cancelHandler = (OrderEventHandler) ctx().getBean("cancelOrderWithRefundOrderEventHandler");
        handler = (OrderEventHandler) ctx().getBean("externalRefundOrderEventHandler");
        orderService = (CustomerOrderService) ctx().getBean(ServiceSpringKeys.CUSTOMER_ORDER_SERVICE);
        paymentService = (CustomerOrderPaymentService) ctx().getBean("customerOrderPaymentService");
    }


    @Test
    public void testHandleStandardPaymentProcessingOnlineAuth() throws Exception {

        configureTestPG(true, true, TestPaymentGatewayImpl.PROCESSING);

        String label = assertPgFeatures("testPaymentGateway", false, true, true, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        deactivateTestPgParameter(TestPaymentGatewayImpl.PROCESSING);

        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", customerOrder.getOrderTotal()))));


        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_RESERVED);

        // Authorisation
        assertSinglePaymentEntry(customerOrder.getOrdernum(), "689.74", PaymentGateway.AUTH, Payment.PAYMENT_STATUS_PROCESSING, false);
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_WAITING_PAYMENT, customerOrder.getOrderStatus());

        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));


        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_VOID_RESERVATION);

        // Authorisation
        assertSinglePaymentEntry(customerOrder.getOrdernum(), "689.74", PaymentGateway.AUTH, Payment.PAYMENT_STATUS_PROCESSING, false);
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());
    }


    @Test
    public void testHandleFullMixedMultiPaymentProcessingPartialOnlineAuthPerShipment() throws Exception {

        configureTestPG(true, true, TestPaymentGatewayImpl.PROCESSING_NO + "84.77");

        String label = assertPgFeatures("testPaymentGateway", false, true, true, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.FULL, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        deactivateTestPgParameter(TestPaymentGatewayImpl.PROCESSING_NO + "84.77");

        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("689.74")))));

        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("259.74")))));

        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("444.95")))));

        // check reserved quantity
        // standard
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");
        // preorder
        assertInventory(WAREHOUSE_ID, "CC_TEST6", "500.00", "3.00");
        // backorder
        assertInventory(WAREHOUSE_ID, "CC_TEST5-NOINV", "0.00", "4.00");
        // electronic
        assertInventory(WAREHOUSE_ID, "CC_TEST9", "0.00", "0.00");

        assertTrue(customerOrder.getDelivery().size() == 4);
        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_RESERVED);

        // external.refund notification has NO effect on AUTH since these funds have NOT yet been captured and
        // hence this flow is an anomaly (only captured payments will send external notification)
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",             "259.74",               "84.77",                "444.95"),
                Arrays.asList(PaymentGateway.AUTH,  PaymentGateway.AUTH,    PaymentGateway.AUTH,    PaymentGateway.AUTH),
                Arrays.asList(Payment.PAYMENT_STATUS_OK, Payment.PAYMENT_STATUS_OK, Payment.PAYMENT_STATUS_PROCESSING, Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE)
        );
        assertEquals("1479.20", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_WAITING_PAYMENT, customerOrder.getOrderStatus());

        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        // standard
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");
        // preorder
        assertInventory(WAREHOUSE_ID, "CC_TEST6", "500.00", "0.00");
        // backorder
        assertInventory(WAREHOUSE_ID, "CC_TEST5-NOINV", "0.00", "0.00");
        // electronic
        assertInventory(WAREHOUSE_ID, "CC_TEST9", "0.00", "0.00");

        assertTrue(customerOrder.getDelivery().size() == 4);
        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_VOID_RESERVATION);

        // Electronic delivery causes Capture, other deliveries are only authorised
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74", "259.74", "84.77", "444.95",
                        "689.74", "259.74", "444.95"),
                Arrays.asList(PaymentGateway.AUTH, PaymentGateway.AUTH, PaymentGateway.AUTH, PaymentGateway.AUTH,
                        PaymentGateway.REVERSE_AUTH, PaymentGateway.REVERSE_AUTH, PaymentGateway.REVERSE_AUTH),
                Arrays.asList(Payment.PAYMENT_STATUS_OK, Payment.PAYMENT_STATUS_OK, Payment.PAYMENT_STATUS_PROCESSING, Payment.PAYMENT_STATUS_OK,
                        Payment.PAYMENT_STATUS_OK, Payment.PAYMENT_STATUS_OK, Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE,
                        Boolean.FALSE, Boolean.FALSE, Boolean.FALSE)
        );
        assertEquals("1479.20", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());
    }



    @Test
    public void testHandleStandardPaymentProcessingOnlineCapture() throws Exception {

        configureTestPG(false, true, TestPaymentGatewayImpl.PROCESSING);

        String label = assertPgFeatures("testPaymentGateway", false, true, false, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        deactivateTestPgParameter(TestPaymentGatewayImpl.PROCESSING);

        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", customerOrder.getOrderTotal()))));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_RESERVED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                         "689.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,      PaymentGateway.VOID_CAPTURE),
                Arrays.asList(Payment.PAYMENT_STATUS_PROCESSING,Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                    Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        // Really strange case but this is acceptable for now as we only want to count paid captures
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_WAITING_PAYMENT, customerOrder.getOrderStatus());

        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_VOID_RESERVATION);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                         "689.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,      PaymentGateway.VOID_CAPTURE),
                Arrays.asList(Payment.PAYMENT_STATUS_PROCESSING,Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                    Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        // Really strange case but this is acceptable for now as we only want to count paid captures
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());
    }

    @Test
    public void testHandleStandardPaymentProcessingOnlineCaptureFailedVoid() throws Exception {

        configureTestPG(false, true, TestPaymentGatewayImpl.PROCESSING);

        String label = assertPgFeatures("testPaymentGateway", false, true, false, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        deactivateTestPgParameter(TestPaymentGatewayImpl.PROCESSING);
        activateTestPgParameterSetOn(TestPaymentGatewayImpl.VOID_CAPTURE_FAIL);

        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", customerOrder.getOrderTotal()))));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_RESERVED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                         "689.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,      PaymentGateway.VOID_CAPTURE),
                Arrays.asList(Payment.PAYMENT_STATUS_PROCESSING,Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                    Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_WAITING_PAYMENT, customerOrder.getOrderStatus());

        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_VOID_RESERVATION);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                         "689.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,      PaymentGateway.VOID_CAPTURE),
                Arrays.asList(Payment.PAYMENT_STATUS_PROCESSING,Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                    Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());

    }



    @Test
    public void testHandleMixedMultiPaymentProcessingOnlineCapturePerShipment() throws Exception {

        configureTestPG(false, true, TestPaymentGatewayImpl.PROCESSING);

        String label = assertPgFeatures("testPaymentGateway", false, true, false, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.MIXED, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        deactivateTestPgParameter(TestPaymentGatewayImpl.PROCESSING);

        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("689.74")))));

        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("84.77")))));

        // check reserved quantity
        // standard
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");
        // preorder
        assertInventory(WAREHOUSE_ID, "CC_TEST6", "500.00", "3.00");
        // backorder
        assertInventory(WAREHOUSE_ID, "CC_TEST5-NOINV", "0.00", "4.00");

        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_RESERVED);

        // Authorisation per delivery
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                             "259.74",                           "84.77",
                        "689.74",                             "84.77"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,          PaymentGateway.AUTH_CAPTURE,        PaymentGateway.AUTH_CAPTURE,
                        PaymentGateway.VOID_CAPTURE,          PaymentGateway.VOID_CAPTURE),
                Arrays.asList(Payment.PAYMENT_STATUS_PROCESSING,    Payment.PAYMENT_STATUS_PROCESSING,  Payment.PAYMENT_STATUS_PROCESSING,
                        Payment.PAYMENT_STATUS_OK,            Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                        Boolean.FALSE,                      Boolean.FALSE,
                        Boolean.FALSE,                        Boolean.FALSE));
        assertEquals("1034.25", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_WAITING_PAYMENT, customerOrder.getOrderStatus());

        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        // standard
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");
        // preorder
        assertInventory(WAREHOUSE_ID, "CC_TEST6", "500.00", "0.00");
        // backorder
        assertInventory(WAREHOUSE_ID, "CC_TEST5-NOINV", "0.00", "0.00");

        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_VOID_RESERVATION);

        // Authorisation per delivery
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                             "259.74",                           "84.77",
                        "689.74",                             "259.74",                           "84.77"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,          PaymentGateway.AUTH_CAPTURE,        PaymentGateway.AUTH_CAPTURE,
                        PaymentGateway.VOID_CAPTURE,          PaymentGateway.VOID_CAPTURE,        PaymentGateway.VOID_CAPTURE),
                Arrays.asList(Payment.PAYMENT_STATUS_PROCESSING,    Payment.PAYMENT_STATUS_PROCESSING,  Payment.PAYMENT_STATUS_PROCESSING,
                        Payment.PAYMENT_STATUS_OK,            Payment.PAYMENT_STATUS_OK,          Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                        Boolean.FALSE,                      Boolean.FALSE,
                        Boolean.FALSE,                        Boolean.FALSE,                      Boolean.FALSE));
        assertEquals("1034.25", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());
    }

    @Test
    public void testHandleMixedMultiPaymentProcessingOnlineCapturePerShipmentVoidFailed() throws Exception {

        configureTestPG(false, true, TestPaymentGatewayImpl.PROCESSING);

        String label = assertPgFeatures("testPaymentGateway", false, true, false, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.MIXED, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        deactivateTestPgParameter(TestPaymentGatewayImpl.PROCESSING);
        activateTestPgParameterSetOn(TestPaymentGatewayImpl.VOID_CAPTURE_FAIL_NO + "259.74");

        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("689.74")))));

        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("84.77")))));

        // check reserved quantity
        // standard
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");
        // preorder
        assertInventory(WAREHOUSE_ID, "CC_TEST6", "500.00", "3.00");
        // backorder
        assertInventory(WAREHOUSE_ID, "CC_TEST5-NOINV", "0.00", "4.00");

        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_RESERVED);

        // Authorisation per delivery
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                             "259.74",                           "84.77",
                        "689.74",                             "84.77"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,          PaymentGateway.AUTH_CAPTURE,        PaymentGateway.AUTH_CAPTURE,
                        PaymentGateway.VOID_CAPTURE,          PaymentGateway.VOID_CAPTURE),
                Arrays.asList(Payment.PAYMENT_STATUS_PROCESSING,    Payment.PAYMENT_STATUS_PROCESSING,  Payment.PAYMENT_STATUS_PROCESSING,
                        Payment.PAYMENT_STATUS_OK,            Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                        Boolean.FALSE,                      Boolean.FALSE,
                        Boolean.FALSE,                        Boolean.FALSE));
        assertEquals("1034.25", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_WAITING_PAYMENT, customerOrder.getOrderStatus());

        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        // standard
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");
        // preorder
        assertInventory(WAREHOUSE_ID, "CC_TEST6", "500.00", "0.00");
        // backorder
        assertInventory(WAREHOUSE_ID, "CC_TEST5-NOINV", "0.00", "0.00");

        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_VOID_RESERVATION);

        // Authorisation per delivery
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                             "259.74",                           "84.77",
                        "689.74",                             "259.74",                           "84.77"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,          PaymentGateway.AUTH_CAPTURE,        PaymentGateway.AUTH_CAPTURE,
                        PaymentGateway.VOID_CAPTURE,          PaymentGateway.VOID_CAPTURE,        PaymentGateway.VOID_CAPTURE),
                Arrays.asList(Payment.PAYMENT_STATUS_PROCESSING,    Payment.PAYMENT_STATUS_PROCESSING,  Payment.PAYMENT_STATUS_PROCESSING,
                        Payment.PAYMENT_STATUS_OK,            Payment.PAYMENT_STATUS_FAILED,      Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                        Boolean.FALSE,                      Boolean.FALSE,
                        Boolean.FALSE,                        Boolean.FALSE,                      Boolean.FALSE));
        assertEquals("1034.25", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED_WAITING_PAYMENT, customerOrder.getOrderStatus());
    }




    @Test
    public void testHandleMixedMultiPaymentProcessingPartialOnlineCapturePerShipment() throws Exception {

        configureTestPG(false, true, TestPaymentGatewayImpl.PROCESSING_NO + "259.74");

        String label = assertPgFeatures("testPaymentGateway", false, true, false, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.MIXED, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        deactivateTestPgParameter(TestPaymentGatewayImpl.PROCESSING_NO + "259.74");

        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("689.74")))));

        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("84.77")))));

        // check reserved quantity
        // standard
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");
        // preorder
        assertInventory(WAREHOUSE_ID, "CC_TEST6", "500.00", "3.00");
        // backorder
        assertInventory(WAREHOUSE_ID, "CC_TEST5-NOINV", "0.00", "4.00");

        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_RESERVED);

        // Authorisation per delivery
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "259.74",                           "84.77",
                        "689.74",                     "84.77"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.AUTH_CAPTURE,        PaymentGateway.AUTH_CAPTURE,
                        PaymentGateway.REFUND,        PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_PROCESSING,  Payment.PAYMENT_STATUS_OK,
                        Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.TRUE,                 Boolean.FALSE,                      Boolean.TRUE,
                        Boolean.FALSE,                Boolean.FALSE));
        assertEquals("1034.25", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_WAITING_PAYMENT, customerOrder.getOrderStatus());

        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        // standard
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");
        // preorder
        assertInventory(WAREHOUSE_ID, "CC_TEST6", "500.00", "0.00");
        // backorder
        assertInventory(WAREHOUSE_ID, "CC_TEST5-NOINV", "0.00", "0.00");

        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_VOID_RESERVATION);

        // Authorisation per delivery
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "259.74",                           "84.77",
                        "689.74",                     "259.74",                           "84.77"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.AUTH_CAPTURE,        PaymentGateway.AUTH_CAPTURE,
                        PaymentGateway.REFUND,        PaymentGateway.VOID_CAPTURE,        PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_PROCESSING,  Payment.PAYMENT_STATUS_OK,
                        Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK,          Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.TRUE,                 Boolean.FALSE,                      Boolean.TRUE,
                        Boolean.FALSE,                Boolean.FALSE,                      Boolean.FALSE));
        assertEquals("1034.25", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());    }


    @Test
    public void testHandleMixedMultiPaymentProcessingPartialOnlineCapturePerShipmentRefund() throws Exception {

        configureTestPG(false, true, TestPaymentGatewayImpl.PROCESSING_NO + "259.74");

        String label = assertPgFeatures("testPaymentGateway", false, true, false, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.MIXED, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        deactivateTestPgParameter(TestPaymentGatewayImpl.PROCESSING_NO + "259.74");
        activateTestPgParameterSetOn(TestPaymentGatewayImpl.REFUND_FAIL_NO + "84.77");

        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("689.74")))));

        // check reserved quantity
        // standard
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");
        // preorder
        assertInventory(WAREHOUSE_ID, "CC_TEST6", "500.00", "3.00");
        // backorder
        assertInventory(WAREHOUSE_ID, "CC_TEST5-NOINV", "0.00", "4.00");

        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_RESERVED);

        // Authorisation per delivery
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "259.74",                           "84.77",
                        "689.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.AUTH_CAPTURE,        PaymentGateway.AUTH_CAPTURE,
                        PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_PROCESSING,  Payment.PAYMENT_STATUS_OK,
                        Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.TRUE,                 Boolean.FALSE,                      Boolean.TRUE,
                        Boolean.FALSE));
        assertEquals("1034.25", customerOrder.getOrderTotal().toPlainString());
        assertEquals("84.77", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_WAITING_PAYMENT, customerOrder.getOrderStatus());

        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        // standard
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");
        // preorder
        assertInventory(WAREHOUSE_ID, "CC_TEST6", "500.00", "0.00");
        // backorder
        assertInventory(WAREHOUSE_ID, "CC_TEST5-NOINV", "0.00", "0.00");

        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_VOID_RESERVATION);

        // Authorisation per delivery
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "259.74",                           "84.77",
                        "689.74",                     "259.74",                           "84.77"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.AUTH_CAPTURE,        PaymentGateway.AUTH_CAPTURE,
                        PaymentGateway.REFUND,        PaymentGateway.VOID_CAPTURE,        PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_PROCESSING,  Payment.PAYMENT_STATUS_OK,
                        Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK,          Payment.PAYMENT_STATUS_FAILED),
                Arrays.asList(Boolean.TRUE,                 Boolean.FALSE,                      Boolean.TRUE,
                        Boolean.FALSE,                Boolean.FALSE,                      Boolean.FALSE));
        assertEquals("1034.25", customerOrder.getOrderTotal().toPlainString());
        assertEquals("84.77", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED_WAITING_PAYMENT, customerOrder.getOrderStatus());
    }




    @Test
    public void testHandleStandardPaymentOkOfflineAuth() throws Exception {

        String label = assertPgFeatures("courierPaymentGateway", false, false, true, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));


        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", customerOrder.getOrderTotal()))));


        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");


        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_RESERVED);

        // no payment because it is made when CC manager approves the order
        assertNoPaymentEntries(customerOrder.getOrdernum());
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_WAITING, customerOrder.getOrderStatus());

        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));


        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");


        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_VOID_RESERVATION);

        // no payment because it is made when CC manager approves the order
        assertNoPaymentEntries(customerOrder.getOrdernum());
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());
    }




    @Test
    public void testHandleMixedMultiPaymentOkOnlineAuthPerShipment() throws Exception {

        String label = assertPgFeatures("testPaymentGateway", false, true, true, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.MIXED, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), new HashMap<String, String>() {{
            put(CustomerOrderDelivery.STANDARD_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);
            put(CustomerOrderDelivery.DATE_WAIT_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_DATE_WAIT);
            put(CustomerOrderDelivery.INVENTORY_WAIT_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_WAIT);
        }});

        // refund just before allocation happened
        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("689.74")))));


        // check reserved quantity
        // standard
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");
        // preorder
        assertInventory(WAREHOUSE_ID, "CC_TEST6", "500.00", "3.00");
        // backorder
        assertInventory(WAREHOUSE_ID, "CC_TEST5-NOINV", "0.00", "4.00");

        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), new HashMap<String, String>() {{
            put(CustomerOrderDelivery.STANDARD_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);
            put(CustomerOrderDelivery.DATE_WAIT_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_DATE_WAIT);
            put(CustomerOrderDelivery.INVENTORY_WAIT_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_WAIT);
        }});

        // Authorisation per delivery
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74", "259.74", "84.77"),
                Arrays.asList(PaymentGateway.AUTH, PaymentGateway.AUTH, PaymentGateway.AUTH),
                Arrays.asList(Payment.PAYMENT_STATUS_OK, Payment.PAYMENT_STATUS_OK, Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE, Boolean.FALSE, Boolean.FALSE));
        assertEquals("1034.25", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_IN_PROGRESS, customerOrder.getOrderStatus());

        // refund just before allocation happened
        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));


        // check reserved quantity
        // standard
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");
        // preorder
        assertInventory(WAREHOUSE_ID, "CC_TEST6", "500.00", "0.00");
        // backorder
        assertInventory(WAREHOUSE_ID, "CC_TEST5-NOINV", "0.00", "0.00");

        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_VOID_RESERVATION);

        // Authorisation per delivery
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74", "259.74", "84.77",
                        "689.74", "259.74", "84.77"),
                Arrays.asList(PaymentGateway.AUTH, PaymentGateway.AUTH, PaymentGateway.AUTH,
                        PaymentGateway.REVERSE_AUTH, PaymentGateway.REVERSE_AUTH, PaymentGateway.REVERSE_AUTH),
                Arrays.asList(Payment.PAYMENT_STATUS_OK, Payment.PAYMENT_STATUS_OK, Payment.PAYMENT_STATUS_OK,
                        Payment.PAYMENT_STATUS_OK, Payment.PAYMENT_STATUS_OK, Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE, Boolean.FALSE, Boolean.FALSE,
                        Boolean.FALSE, Boolean.FALSE, Boolean.FALSE));
        assertEquals("1034.25", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());
    }



    @Test
    public void testHandleMixedMultiPaymentOkOnlineCapturePerShipment() throws Exception {

        configureTestPG(false, true);

        String label = assertPgFeatures("testPaymentGateway", false, true, false, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.MIXED, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));


        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), new HashMap<String, String>() {{
            put(CustomerOrderDelivery.STANDARD_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);
            put(CustomerOrderDelivery.DATE_WAIT_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_DATE_WAIT);
            put(CustomerOrderDelivery.INVENTORY_WAIT_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_WAIT);
        }});

        // refund just before allocation happened
        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("689.74")))));

        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("259.74")))));

        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("50.00")))));


        // check reserved quantity
        // standard
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");
        // preorder
        assertInventory(WAREHOUSE_ID, "CC_TEST6", "500.00", "3.00");
        // backorder
        assertInventory(WAREHOUSE_ID, "CC_TEST5-NOINV", "0.00", "4.00");

        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), new HashMap<String, String>() {{
            put(CustomerOrderDelivery.STANDARD_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);
            put(CustomerOrderDelivery.DATE_WAIT_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_DATE_WAIT);
            put(CustomerOrderDelivery.INVENTORY_WAIT_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_WAIT);
        }});

        // Capture per delivery
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "259.74",                       "84.77",
                        "689.74",                     "259.74",                       "50.00"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.AUTH_CAPTURE,    PaymentGateway.AUTH_CAPTURE,
                        PaymentGateway.REFUND,        PaymentGateway.REFUND,          PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK,      Payment.PAYMENT_STATUS_OK,
                        Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK,      Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.TRUE,                 Boolean.TRUE,                   Boolean.TRUE,
                        Boolean.FALSE,                Boolean.FALSE,                  Boolean.FALSE));
        assertEquals("1034.25", customerOrder.getOrderTotal().toPlainString());
        assertEquals("34.77", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_IN_PROGRESS, customerOrder.getOrderStatus());

        // refund just before allocation happened
        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));


        // check reserved quantity
        // standard
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");
        // preorder
        assertInventory(WAREHOUSE_ID, "CC_TEST6", "500.00", "0.00");
        // backorder
        assertInventory(WAREHOUSE_ID, "CC_TEST5-NOINV", "0.00", "0.00");

        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_VOID_RESERVATION);

        // Capture per delivery
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "259.74",                       "84.77",
                        "689.74",                     "259.74",                       "50.00",                              "34.77"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.AUTH_CAPTURE,    PaymentGateway.AUTH_CAPTURE,
                        PaymentGateway.REFUND,        PaymentGateway.REFUND,          PaymentGateway.REFUND,                PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK,      Payment.PAYMENT_STATUS_OK,
                        Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK,      Payment.PAYMENT_STATUS_OK,            Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.TRUE,                 Boolean.TRUE,                   Boolean.TRUE,
                        Boolean.FALSE,                Boolean.FALSE,                  Boolean.FALSE,                        Boolean.FALSE));
        assertEquals("1034.25", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());
    }

    @Test
    public void testHandleMixedMultiPaymentOkOnlineCapturePerShipmentRefundFailed() throws Exception {

        configureTestPG(false, true, TestPaymentGatewayImpl.REFUND_FAIL_NO + "84.77");

        String label = assertPgFeatures("testPaymentGateway", false, true, false, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.MIXED, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));


        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), new HashMap<String, String>() {{
            put(CustomerOrderDelivery.STANDARD_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);
            put(CustomerOrderDelivery.DATE_WAIT_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_DATE_WAIT);
            put(CustomerOrderDelivery.INVENTORY_WAIT_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_WAIT);
        }});

        // refund just before allocation happened
        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("689.74")))));
        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("259.74")))));


        // check reserved quantity
        // standard
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");
        // preorder
        assertInventory(WAREHOUSE_ID, "CC_TEST6", "500.00", "3.00");
        // backorder
        assertInventory(WAREHOUSE_ID, "CC_TEST5-NOINV", "0.00", "4.00");

        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), new HashMap<String, String>() {{
            put(CustomerOrderDelivery.STANDARD_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);
            put(CustomerOrderDelivery.DATE_WAIT_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_DATE_WAIT);
            put(CustomerOrderDelivery.INVENTORY_WAIT_DELIVERY_GROUP, CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_WAIT);
        }});

        // Capture per delivery
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "259.74",                       "84.77",
                        "689.74",                   "259.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.AUTH_CAPTURE,    PaymentGateway.AUTH_CAPTURE,
                        PaymentGateway.REFUND,      PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK,      Payment.PAYMENT_STATUS_OK,
                        Payment.PAYMENT_STATUS_OK,  Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.TRUE,                 Boolean.TRUE,                   Boolean.TRUE,
                        Boolean.TRUE,                Boolean.TRUE));
        assertEquals("1034.25", customerOrder.getOrderTotal().toPlainString());
        assertEquals("84.77", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_IN_PROGRESS, customerOrder.getOrderStatus());

        // cancel after refund
        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.emptyMap())));


        // check reserved quantity
        // standard
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");
        // preorder
        assertInventory(WAREHOUSE_ID, "CC_TEST6", "500.00", "0.00");
        // backorder
        assertInventory(WAREHOUSE_ID, "CC_TEST5-NOINV", "0.00", "0.00");

        assertEquals(customerOrder.getDelivery().size(), 3);
        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_VOID_RESERVATION);

        // Capture per delivery
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "259.74",                       "84.77",
                        "689.74",                   "259.74",                   "84.77"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.AUTH_CAPTURE,    PaymentGateway.AUTH_CAPTURE,
                        PaymentGateway.REFUND,      PaymentGateway.REFUND,  PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK,      Payment.PAYMENT_STATUS_OK,
                        Payment.PAYMENT_STATUS_OK,  Payment.PAYMENT_STATUS_OK,  Payment.PAYMENT_STATUS_FAILED),
                Arrays.asList(Boolean.TRUE,                 Boolean.TRUE,                   Boolean.TRUE,
                        Boolean.TRUE,                Boolean.TRUE,             Boolean.TRUE));
        assertEquals("1034.25", customerOrder.getOrderTotal().toPlainString());
        assertEquals("84.77", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED_WAITING_PAYMENT, customerOrder.getOrderStatus());
    }


    @Test
    public void testHandleStandardInventoryAllocatedAuth() throws Exception {

        String label = assertPgFeatures("testPaymentGateway", false, true, true, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);

        CustomerOrderDelivery delivery = null;
        for (final CustomerOrderDelivery orderDelivery : customerOrder.getDelivery()) {
            if (CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT.equals(orderDelivery.getDeliveryStatus())) {
                assertNull(delivery); // make sure there is only one!
                delivery = orderDelivery;
            }
        }

        assertTrue(allocationHandler.handle(
                new OrderEventImpl("", //evt.process.allocation
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_ALLOCATED);

        // refund after allocation happened
        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", customerOrder.getOrderTotal()))));



        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_ALLOCATED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Collections.singletonList("689.74"),
                Collections.singletonList(PaymentGateway.AUTH),
                Collections.singletonList(Payment.PAYMENT_STATUS_OK),
                Collections.singletonList(Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_IN_PROGRESS, customerOrder.getOrderStatus());

        // refund after allocation happened
        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));



        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_DEALLOCATED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74"),
                Arrays.asList(PaymentGateway.AUTH,          PaymentGateway.REVERSE_AUTH),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());
    }

    @Test
    public void testHandleStandardInventoryPackingAuth() throws Exception {

        String label = assertPgFeatures("testPaymentGateway", false, true, true, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);

        CustomerOrderDelivery delivery = null;
        for (final CustomerOrderDelivery orderDelivery : customerOrder.getDelivery()) {
            if (CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT.equals(orderDelivery.getDeliveryStatus())) {
                assertNull(delivery); // make sure there is only one!
                delivery = orderDelivery;
            }
        }

        assertTrue(allocationHandler.handle(
                new OrderEventImpl("", //evt.process.allocation
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(packingHandler.handle(
                new OrderEventImpl("", //evt.release.to.pack
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_PACKING);

        // refund after allocation happened
        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", customerOrder.getOrderTotal()))));



        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_PACKING);

        // Authorisation
        assertSinglePaymentEntry(customerOrder.getOrdernum(),
                "689.74", PaymentGateway.AUTH, Payment.PAYMENT_STATUS_OK, Boolean.FALSE);
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_IN_PROGRESS, customerOrder.getOrderStatus());

        // refund after allocation happened
        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));



        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_DEALLOCATED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74"),
                Arrays.asList(PaymentGateway.AUTH,          PaymentGateway.REVERSE_AUTH),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());
    }


    @Test
    public void testHandleStandardInventoryShipReadyAuth() throws Exception {

        String label = assertPgFeatures("testPaymentGateway", false, true, true, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);

        CustomerOrderDelivery delivery = null;
        for (final CustomerOrderDelivery orderDelivery : customerOrder.getDelivery()) {
            if (CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT.equals(orderDelivery.getDeliveryStatus())) {
                assertNull(delivery); // make sure there is only one!
                delivery = orderDelivery;
            }
        }

        assertTrue(allocationHandler.handle(
                new OrderEventImpl("", //evt.process.allocation
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(packingHandler.handle(
                new OrderEventImpl("", //evt.release.to.pack
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(packedHandler.handle(
                new OrderEventImpl("", //evt.packing.complete
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPMENT_READY);

        // refund after allocation happened
        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", customerOrder.getOrderTotal()))));



        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPMENT_READY);

        // Authorisation
        assertSinglePaymentEntry(customerOrder.getOrdernum(),
                "689.74", PaymentGateway.AUTH, Payment.PAYMENT_STATUS_OK, Boolean.FALSE);
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_IN_PROGRESS, customerOrder.getOrderStatus());

        // refund after allocation happened
        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));



        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_DEALLOCATED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74"),
                Arrays.asList(PaymentGateway.AUTH,          PaymentGateway.REVERSE_AUTH),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());
    }

    @Test
    public void testHandleStandardInventoryShippingAuthPartial() throws Exception {

        String label = assertPgFeatures("testPaymentGateway", false, true, true, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);

        CustomerOrderDelivery delivery = null;
        for (final CustomerOrderDelivery orderDelivery : customerOrder.getDelivery()) {
            if (CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT.equals(orderDelivery.getDeliveryStatus())) {
                assertNull(delivery); // make sure there is only one!
                delivery = orderDelivery;
            }
        }

        assertTrue(allocationHandler.handle(
                new OrderEventImpl("", //evt.process.allocation
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(packingHandler.handle(
                new OrderEventImpl("", //evt.release.to.pack
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(packedHandler.handle(
                new OrderEventImpl("", //evt.packing.complete
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(releaseHandler.handle(
                new OrderEventImpl("", //evt.release.to.shipment
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPMENT_IN_PROGRESS);

        // refund during shipping
        handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("50.00"))));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPMENT_IN_PROGRESS);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74",                   "50.00"),
                Arrays.asList(PaymentGateway.AUTH,          PaymentGateway.CAPTURE,     PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK,  Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                Boolean.TRUE,               Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("639.74", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_IN_PROGRESS, customerOrder.getOrderStatus());

        orderService.update(customerOrder);

        try {
            // refund during shipping
            cancelHandler.handle(
                    new OrderEventImpl("", //evt.order.cancel.refund
                            customerOrder,
                            null,
                            Collections.EMPTY_MAP));
            fail("Cancellation during shipping in progress is not allowed");
        } catch (OrderException oe) {
            // expected as we are shipping and need to wait till customer receives it and returns
        }

        // refresh after exception
        customerOrder = orderService.findByReference(customerOrder.getOrdernum());

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPMENT_IN_PROGRESS);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74",                   "50.00"),
                Arrays.asList(PaymentGateway.AUTH,          PaymentGateway.CAPTURE,     PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK,  Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                Boolean.TRUE,               Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("639.74", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_IN_PROGRESS, customerOrder.getOrderStatus());
    }



    @Test
    public void testHandleStandardInventoryShippedAuth() throws Exception {

        String label = assertPgFeatures("testPaymentGateway", false, true, true, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);

        CustomerOrderDelivery delivery = null;
        for (final CustomerOrderDelivery orderDelivery : customerOrder.getDelivery()) {
            if (CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT.equals(orderDelivery.getDeliveryStatus())) {
                assertNull(delivery); // make sure there is only one!
                delivery = orderDelivery;
            }
        }

        assertTrue(allocationHandler.handle(
                new OrderEventImpl("", //evt.process.allocation
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(packingHandler.handle(
                new OrderEventImpl("", //evt.release.to.pack
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(packedHandler.handle(
                new OrderEventImpl("", //evt.packing.complete
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(releaseHandler.handle(
                new OrderEventImpl("", //evt.release.to.shipment
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(shippedHandler.handle(
                new OrderEventImpl("", //evt.shipment.complete
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPPED);
        assertEquals(CustomerOrder.ORDER_STATUS_COMPLETED, customerOrder.getOrderStatus());

        // refund for completed order
        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", customerOrder.getOrderTotal()))));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPPED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74",                       "689.74"),
                Arrays.asList(PaymentGateway.AUTH,          PaymentGateway.CAPTURE,         PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK,      Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                Boolean.TRUE,                   Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_COMPLETED, customerOrder.getOrderStatus());

        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_RETURNED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74",                       "689.74"),
                Arrays.asList(PaymentGateway.AUTH,          PaymentGateway.CAPTURE,         PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK,      Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                Boolean.TRUE,                   Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_RETURNED, customerOrder.getOrderStatus());
    }



    @Test
    public void testHandleStandardInventoryShippedAuthRefundFailed() throws Exception {

        configureTestPG(true, true, TestPaymentGatewayImpl.REFUND_FAIL);

        String label = assertPgFeatures("testPaymentGateway", false, true, true, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);

        CustomerOrderDelivery delivery = null;
        for (final CustomerOrderDelivery orderDelivery : customerOrder.getDelivery()) {
            if (CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT.equals(orderDelivery.getDeliveryStatus())) {
                assertNull(delivery); // make sure there is only one!
                delivery = orderDelivery;
            }
        }

        assertTrue(allocationHandler.handle(
                new OrderEventImpl("", //evt.process.allocation
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(packingHandler.handle(
                new OrderEventImpl("", //evt.release.to.pack
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(packedHandler.handle(
                new OrderEventImpl("", //evt.packing.complete
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(releaseHandler.handle(
                new OrderEventImpl("", //evt.release.to.shipment
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(shippedHandler.handle(
                new OrderEventImpl("", //evt.shipment.complete
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPPED);
        assertEquals(CustomerOrder.ORDER_STATUS_COMPLETED, customerOrder.getOrderStatus());

        // refund for completed order (i.e. return)
        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("50.00")))));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPPED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74",                       "50.00"),
                Arrays.asList(PaymentGateway.AUTH,          PaymentGateway.CAPTURE,         PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK,      Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                Boolean.TRUE,                   Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("639.74", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_COMPLETED, customerOrder.getOrderStatus());

        // refund for completed order (i.e. return)
        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_RETURNED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74",                       "50.00",                        "639.74"),
                Arrays.asList(PaymentGateway.AUTH,          PaymentGateway.CAPTURE,         PaymentGateway.REFUND,          PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK,      Payment.PAYMENT_STATUS_OK,      Payment.PAYMENT_STATUS_FAILED),
                Arrays.asList(Boolean.FALSE,                Boolean.TRUE,                   Boolean.FALSE,                  Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("639.74", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_RETURNED_WAITING_PAYMENT, customerOrder.getOrderStatus());
    }



    @Test
    public void testHandleStandardInventoryShippingAuthNoCapture() throws Exception {

        configureTestPG(true, true, TestPaymentGatewayImpl.CAPTURE_FAIL);

        String label = assertPgFeatures("testPaymentGateway", false, true, true, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);

        CustomerOrderDelivery delivery = null;
        for (final CustomerOrderDelivery orderDelivery : customerOrder.getDelivery()) {
            if (CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT.equals(orderDelivery.getDeliveryStatus())) {
                assertNull(delivery); // make sure there is only one!
                delivery = orderDelivery;
            }
        }

        assertTrue(allocationHandler.handle(
                new OrderEventImpl("", //evt.process.allocation
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(packingHandler.handle(
                new OrderEventImpl("", //evt.release.to.pack
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(packedHandler.handle(
                new OrderEventImpl("", //evt.packing.complete
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(releaseHandler.handle(
                new OrderEventImpl("", //evt.release.to.shipment
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPMENT_READY_WAITING_PAYMENT);

        // refund during shipping
        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", customerOrder.getOrderTotal()))));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPMENT_READY_WAITING_PAYMENT);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74"),
                Arrays.asList(PaymentGateway.AUTH,          PaymentGateway.CAPTURE),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_FAILED),
                Arrays.asList(Boolean.FALSE,                Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_IN_PROGRESS, customerOrder.getOrderStatus());

        // refund during shipping
        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_DEALLOCATED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74",                       "689.74"),
                Arrays.asList(PaymentGateway.AUTH,          PaymentGateway.CAPTURE,         PaymentGateway.REVERSE_AUTH),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_FAILED,  Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                Boolean.FALSE,                  Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());
    }


    @Test
    public void testHandleStandardInventoryAllocatedCapture() throws Exception {

        configureTestPG(false, true);

        String label = assertPgFeatures("testPaymentGateway", false, true, false, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);

        CustomerOrderDelivery delivery = null;
        for (final CustomerOrderDelivery orderDelivery : customerOrder.getDelivery()) {
            if (CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT.equals(orderDelivery.getDeliveryStatus())) {
                assertNull(delivery); // make sure there is only one!
                delivery = orderDelivery;
            }
        }

        assertTrue(allocationHandler.handle(
                new OrderEventImpl("", //evt.process.allocation
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_ALLOCATED);

        // refund after allocation happened
        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", customerOrder.getOrderTotal()))));



        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_ALLOCATED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.TRUE,                 Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_IN_PROGRESS, customerOrder.getOrderStatus());

        // cancel after refund
        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));



        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_DEALLOCATED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.TRUE,                 Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());
    }

    @Test
    public void testHandleStandardInventoryPackingCapture() throws Exception {

        configureTestPG(false, true);

        String label = assertPgFeatures("testPaymentGateway", false, true, false, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);

        CustomerOrderDelivery delivery = null;
        for (final CustomerOrderDelivery orderDelivery : customerOrder.getDelivery()) {
            if (CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT.equals(orderDelivery.getDeliveryStatus())) {
                assertNull(delivery); // make sure there is only one!
                delivery = orderDelivery;
            }
        }

        assertTrue(allocationHandler.handle(
                new OrderEventImpl("", //evt.process.allocation
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));


        assertTrue(packingHandler.handle(
                new OrderEventImpl("", //evt.release.to.pack
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_PACKING);

        // refund after allocation happened
        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", customerOrder.getOrderTotal()))));



        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_PACKING);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.TRUE,                 Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_IN_PROGRESS, customerOrder.getOrderStatus());

        // refund after allocation happened
        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));



        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_DEALLOCATED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.TRUE,                 Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());
    }

    @Test
    public void testHandleStandardInventoryShipReadyCapture() throws Exception {

        configureTestPG(false, true);

        String label = assertPgFeatures("testPaymentGateway", false, true, false, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);

        CustomerOrderDelivery delivery = null;
        for (final CustomerOrderDelivery orderDelivery : customerOrder.getDelivery()) {
            if (CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT.equals(orderDelivery.getDeliveryStatus())) {
                assertNull(delivery); // make sure there is only one!
                delivery = orderDelivery;
            }
        }

        assertTrue(allocationHandler.handle(
                new OrderEventImpl("", //evt.process.allocation
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));


        assertTrue(packingHandler.handle(
                new OrderEventImpl("", //evt.release.to.pack
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(packedHandler.handle(
                new OrderEventImpl("", //evt.packing.complete
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPMENT_READY);

        // refund after allocation happened
        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", customerOrder.getOrderTotal()))));



        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPMENT_READY);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.TRUE,                 Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_IN_PROGRESS, customerOrder.getOrderStatus());

        // refund after allocation happened
        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));



        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_DEALLOCATED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.TRUE,                 Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());
    }


    @Test
    public void testHandleStandardInventoryShippingCapture() throws Exception {

        configureTestPG(false, true);

        String label = assertPgFeatures("testPaymentGateway", false, true, false, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);

        CustomerOrderDelivery delivery = null;
        for (final CustomerOrderDelivery orderDelivery : customerOrder.getDelivery()) {
            if (CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT.equals(orderDelivery.getDeliveryStatus())) {
                assertNull(delivery); // make sure there is only one!
                delivery = orderDelivery;
            }
        }

        assertTrue(allocationHandler.handle(
                new OrderEventImpl("", //evt.process.allocation
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));


        assertTrue(packingHandler.handle(
                new OrderEventImpl("", //evt.release.to.pack
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(packedHandler.handle(
                new OrderEventImpl("", //evt.packing.complete
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));


        assertTrue(releaseHandler.handle(
                new OrderEventImpl("", //evt.release.to.shipment
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPMENT_IN_PROGRESS);

        orderService.update(customerOrder);

        // refund during shipping
        handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("50.00"))));

        // refresh after exception
        customerOrder = orderService.findByReference(customerOrder.getOrdernum());

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPMENT_IN_PROGRESS);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                         "50.00"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,      PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,        Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.TRUE,                     Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("639.74", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_IN_PROGRESS, customerOrder.getOrderStatus());
    }

    @Test
    public void testHandleStandardInventoryShippedCapture() throws Exception {

        configureTestPG(false, true);

        String label = assertPgFeatures("testPaymentGateway", false, true, false, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);

        CustomerOrderDelivery delivery = null;
        for (final CustomerOrderDelivery orderDelivery : customerOrder.getDelivery()) {
            if (CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT.equals(orderDelivery.getDeliveryStatus())) {
                assertNull(delivery); // make sure there is only one!
                delivery = orderDelivery;
            }
        }

        assertTrue(allocationHandler.handle(
                new OrderEventImpl("", //evt.process.allocation
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));


        assertTrue(packingHandler.handle(
                new OrderEventImpl("", //evt.release.to.pack
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(packedHandler.handle(
                new OrderEventImpl("", //evt.packing.complete
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));


        assertTrue(releaseHandler.handle(
                new OrderEventImpl("", //evt.release.to.shipment
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(shippedHandler.handle(
                new OrderEventImpl("", //evt.shipment.complete
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPPED);
        assertEquals(CustomerOrder.ORDER_STATUS_COMPLETED, customerOrder.getOrderStatus());

        // refund for completed order (i.e. return)
        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", customerOrder.getOrderTotal()))));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPPED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.TRUE,                 Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_COMPLETED, customerOrder.getOrderStatus());

        // refund for completed order (i.e. return)
        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_RETURNED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "689.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.TRUE,                 Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_RETURNED, customerOrder.getOrderStatus());
    }

    @Test
    public void testHandleStandardInventoryShippedCaptureRefundPartial() throws Exception {

        configureTestPG(false, true, TestPaymentGatewayImpl.REFUND_FAIL);

        String label = assertPgFeatures("testPaymentGateway", false, true, false, true);

        CustomerOrder customerOrder = createTestOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT);

        CustomerOrderDelivery delivery = null;
        for (final CustomerOrderDelivery orderDelivery : customerOrder.getDelivery()) {
            if (CustomerOrderDelivery.DELIVERY_STATUS_ALLOCATION_WAIT.equals(orderDelivery.getDeliveryStatus())) {
                assertNull(delivery); // make sure there is only one!
                delivery = orderDelivery;
            }
        }

        assertTrue(allocationHandler.handle(
                new OrderEventImpl("", //evt.process.allocation
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));


        assertTrue(packingHandler.handle(
                new OrderEventImpl("", //evt.release.to.pack
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(packedHandler.handle(
                new OrderEventImpl("", //evt.packing.complete
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));


        assertTrue(releaseHandler.handle(
                new OrderEventImpl("", //evt.release.to.shipment
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertTrue(shippedHandler.handle(
                new OrderEventImpl("", //evt.shipment.complete
                        customerOrder,
                        delivery,
                        Collections.EMPTY_MAP)));

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPPED);
        assertEquals(CustomerOrder.ORDER_STATUS_COMPLETED, customerOrder.getOrderStatus());

        // refund for completed order (i.e. return)
        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", new BigDecimal("50.00")))));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_SHIPPED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "50.00"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.TRUE,                 Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("639.74", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_COMPLETED, customerOrder.getOrderStatus());

        // refund for completed order (i.e. return)
        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "7.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "0.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_RETURNED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                     "50.00",                        "639.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,  PaymentGateway.REFUND,          PaymentGateway.REFUND),
                Arrays.asList(Payment.PAYMENT_STATUS_OK,    Payment.PAYMENT_STATUS_OK,      Payment.PAYMENT_STATUS_FAILED),
                Arrays.asList(Boolean.TRUE,                 Boolean.FALSE,                  Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        assertEquals("639.74", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_RETURNED_WAITING_PAYMENT, customerOrder.getOrderStatus());
    }




    @Test
    public void testHandleStandardPaymentProcessingOnlineCaptureSub() throws Exception {

        configureTestPG(false, true, TestPaymentGatewayImpl.PROCESSING);

        String label = assertPgFeatures("testPaymentGateway", false, true, false, true);

        CustomerOrder customerOrder = createTestSubOrder(AbstractEventHandlerImplTest.TestOrderType.STANDARD, label, false);

        assertTrue(pendingHandler.handle(
                new OrderEventImpl("", //evt.pending
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        deactivateTestPgParameter(TestPaymentGatewayImpl.PROCESSING);

        assertTrue(handler.handle(
                new OrderEventImpl("", //evt.refund.external
                        customerOrder,
                        null,
                        Collections.singletonMap("refundNotificationAmount", customerOrder.getOrderTotal()))));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "2.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "1.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_RESERVED);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                             "689.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,          PaymentGateway.VOID_CAPTURE),
                Arrays.asList(Payment.PAYMENT_STATUS_PROCESSING,    Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                        Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        // Really strange case but this is acceptable for now as we only want to count paid captures
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_WAITING_PAYMENT, customerOrder.getOrderStatus());

        assertTrue(cancelHandler.handle(
                new OrderEventImpl("", //evt.order.cancel.refund
                        customerOrder,
                        null,
                        Collections.EMPTY_MAP)));

        // check reserved quantity
        assertInventory(WAREHOUSE_ID, "CC_TEST1", "9.00", "0.00");
        assertInventory(WAREHOUSE_ID, "CC_TEST2", "1.00", "0.00");

        assertDeliveryStates(customerOrder.getDelivery(), CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_VOID_RESERVATION);

        // Authorisation
        assertMultiPaymentEntry(customerOrder.getOrdernum(),
                Arrays.asList("689.74",                         "689.74"),
                Arrays.asList(PaymentGateway.AUTH_CAPTURE,      PaymentGateway.VOID_CAPTURE),
                Arrays.asList(Payment.PAYMENT_STATUS_PROCESSING,Payment.PAYMENT_STATUS_OK),
                Arrays.asList(Boolean.FALSE,                    Boolean.FALSE));
        assertEquals("689.74", customerOrder.getOrderTotal().toPlainString());
        // Really strange case but this is acceptable for now as we only want to count paid captures
        assertEquals("0.00", paymentService.getOrderAmount(customerOrder.getOrdernum()).toPlainString());

        assertEquals(CustomerOrder.ORDER_STATUS_CANCELLED, customerOrder.getOrderStatus());
    }


}