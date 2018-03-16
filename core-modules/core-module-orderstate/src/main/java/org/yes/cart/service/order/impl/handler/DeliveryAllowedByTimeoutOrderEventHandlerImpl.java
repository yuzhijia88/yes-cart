/*
 * Copyright 2009 Denys Pavlov, Igor Azarnyi
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.yes.cart.service.order.impl.handler;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.yes.cart.domain.entity.CustomerOrderDelivery;
import org.yes.cart.domain.entity.CustomerOrderDeliveryDet;
import org.yes.cart.domain.entity.Product;
import org.yes.cart.service.domain.ProductService;
import org.yes.cart.service.order.OrderEvent;
import org.yes.cart.service.order.OrderEventHandler;
import org.yes.cart.service.order.OrderException;
import org.yes.cart.service.order.OrderStateManager;
import org.yes.cart.service.order.impl.OrderEventImpl;
import org.yes.cart.util.TimeContext;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * Perform transition from time  wait to inventory wait state.
 * <p/>
 * * User: Igor Azarny iazarny@yahoo.com
 * Date: 09-May-2011
 * Time: 14:12:54
 */
public class DeliveryAllowedByTimeoutOrderEventHandlerImpl implements OrderEventHandler, ApplicationContextAware {

    private OrderStateManager orderStateManager = null;
    private ApplicationContext applicationContext;

    private final ProductService productService;

    public DeliveryAllowedByTimeoutOrderEventHandlerImpl(final ProductService productService) {
        this.productService = productService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean handle(final OrderEvent orderEvent)  throws OrderException {
        synchronized (OrderEventHandler.syncMonitor) {
            final LocalDateTime now = now();

            final Collection<CustomerOrderDeliveryDet> deliveryDetails = orderEvent.getCustomerOrderDelivery().getDetail();

            for (CustomerOrderDeliveryDet det : deliveryDetails) {

                final Product product = productService.getProductBySkuCode(det.getProductSkuCode());
                final LocalDateTime availableFrom = product.getAvailablefrom();
                if ((availableFrom != null) && (availableFrom.isAfter(now))) {
                    return false; // no transition, because need to wait
                }
            }

            orderEvent.getCustomerOrderDelivery().setDeliveryStatus(CustomerOrderDelivery.DELIVERY_STATUS_INVENTORY_WAIT);
            getOrderStateManager().fireTransition(new OrderEventImpl(orderEvent, OrderStateManager.EVT_DELIVERY_ALLOWED_QUANTITY, orderEvent.getCustomerOrder(), orderEvent.getCustomerOrderDelivery()));

            return true;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setApplicationContext(final ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    LocalDateTime now() {
        return TimeContext.getLocalDateTime();
    }

    private synchronized OrderStateManager getOrderStateManager() {
        if (orderStateManager == null) {
            orderStateManager = (OrderStateManager) applicationContext.getBean("orderStateManager");
        }
        return orderStateManager;
    }


}
