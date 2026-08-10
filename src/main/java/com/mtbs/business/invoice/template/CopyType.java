package com.mtbs.business.invoice.template;

/**
 * Which physical copy is being printed (GST-invoice convention: original
 * for the recipient, duplicate for the supplier's own records). Chosen
 * per print request via a query param — NOT a ShopSettings field, since
 * it varies each time you print, not a standing shop preference.
 */
public enum CopyType {
    ORIGINAL,
    DUPLICATE,
    TRIPLICATE
}
