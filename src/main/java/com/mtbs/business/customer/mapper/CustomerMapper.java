package com.mtbs.business.customer.mapper;

import com.mtbs.business.customer.dto.CustomerResponse;
import com.mtbs.business.customer.entity.Customer;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CustomerMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "phone", source = "phone")
    @Mapping(target = "address", source = "address")
    @Mapping(target = "gstin", source = "gstin")
    @Mapping(target = "razorpayCustomerId", source = "razorpayCustomerId")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    CustomerResponse toResponse(Customer entity);
}
