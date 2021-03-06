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





package org.yes.cart.icecat.transform.csv

import org.yes.cart.icecat.transform.domain.ProductPointer
import org.yes.cart.icecat.transform.Util
import java.math.RoundingMode

/**
 * User: denispavlov
 * Date: 12-08-09
 * Time: 10:15 PM
 */
class ProductPricesCsvAdapter {

    Map<String, ProductPointer> productMap;

    ProductPricesCsvAdapter(final Map<String, ProductPointer> productMap) {
        this.productMap = productMap
    }

    public toCsvFile(String filename) {

        StringBuilder builder = new StringBuilder();
        builder.append("sku code;model;shop code;currency;list price;tier;sale price\n");

        productMap.values().each {
            def pp = it;
            pp.prices.each {
                def sh = it;
                sh.value.each {
                    builder.append('"')
                    builder.append(pp.Prod_ID).append('";"')
                    builder.append(Util.escapeCSV(pp.Model_Name)).append('";"')
                    builder.append(sh.key).append('";"')
                    builder.append(it.key).append('";')    // currency
                    builder.append(it.value).append(';') // price
                    builder.append('1;') // tier
                    if (pp.SpecialOffer) {
                        builder.append(it.value.multiply(new BigDecimal("0.8").setScale(2, RoundingMode.HALF_EVEN))).append('\n') // sale price
                    } else if (pp.Sale) {
                        builder.append(it.value.multiply(new BigDecimal("0.9").setScale(2, RoundingMode.HALF_EVEN))).append('\n') // sale price
                    } else {
                        builder.append('\n') // sale price
                    }
                }
            }
        }
        new File(filename).write(builder.toString(), 'UTF-8');

    }
}
