package legacy.saasbilling.billing.mapper;

import legacy.saasbilling.billing.dto.InvoiceLineItemResponse;
import legacy.saasbilling.billing.entity.InvoiceLineItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface InvoiceLineItemMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "quantity", source = "quantity")
    @Mapping(target = "unitPrice", source = "unitPrice")
    @Mapping(target = "totalPrice", source = "totalPrice")
    @Mapping(target = "lineItemType", source = "lineItemType")
    InvoiceLineItemResponse toResponse(InvoiceLineItem entity);
}
