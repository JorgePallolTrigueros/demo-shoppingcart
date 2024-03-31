package com.shoppingcart.demo.service;

import com.shoppingcart.demo.dao.entity.ProductEntity;
import com.shoppingcart.demo.dao.entity.ProductShoppingCartEntity;
import com.shoppingcart.demo.dao.entity.ShoppingCartItemEntity;
import com.shoppingcart.demo.dao.repository.ProductEntityRepository;
import com.shoppingcart.demo.dao.repository.ShoppingCartEntityRepository;
import com.shoppingcart.demo.exception.ProductNotFoundException;
import com.shoppingcart.demo.exception.ShoppingCartInvalidProductsException;
import com.shoppingcart.demo.exception.ShoppingCartNotFoundException;
import com.shoppingcart.model.InvoiceItem;
import com.shoppingcart.model.Product;
import com.shoppingcart.model.ShoppingCartItem;
import com.shoppingcart.model.ShoppingCartItemRequest;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
@Qualifier("ShoppingCartServiceJpa")
public class ShoppingCartServiceJpaImpl implements ShoppingCartService{

    private final ShoppingCartEntityRepository shoppingCartEntityRepository;
    private final ProductEntityRepository productEntityRepository;
    
    @PostConstruct
    void init(){
        log.info("Se ha creado: ShoppingCartServiceJpaImpl");
    }

    @Override
    public List<ShoppingCartItem> getAllShoppingCarts() {
        return shoppingCartEntityRepository
                .findAll()
                .stream()
                .map(
                shoppingCartItemEntity -> {
                    final var item  = new ShoppingCartItem();
                    item.setId(shoppingCartItemEntity.getId());
                    AtomicReference<BigDecimal> subtotal = new AtomicReference<>(BigDecimal.ZERO);
                    /*
                    id          - carrito1
                    subtotal    - 40
                    productos   -1  Producto1(1,2,3)
                                -2  Producto2(1,2,3)
                                -3  Producto3(1,2,3)

                     */
                    List<Product> products =  shoppingCartItemEntity
                            .getProducts()
                            .stream()
                            .map(productShoppingCartEntity -> getProductFromEntity(productShoppingCartEntity, subtotal))
                            .toList();

                    item.products(products);
                    item.subtotal(subtotal.get());

                    return item;
                })
                .toList();
    }

    @Override
    public Optional<ShoppingCartItem> getShoppingCartByUserId(String userId) {
        Optional<ShoppingCartItemEntity> shoppingCartEntityOptional = shoppingCartEntityRepository.findById(userId);

        if(shoppingCartEntityOptional.isEmpty()){
            return Optional.empty();
        }

        // existe el dato
        ShoppingCartItemEntity shoppingCartItemEntity = shoppingCartEntityOptional.get();
        ShoppingCartItem response = new ShoppingCartItem();

        AtomicReference<BigDecimal> subtotal =  new AtomicReference<>();
        subtotal.set(BigDecimal.ZERO);

        response.setId(shoppingCartItemEntity.getId());
        response.setProducts(shoppingCartItemEntity
                .getProducts()
                .stream()
                .map(productShoppingCartEntity -> getProductFromEntity(productShoppingCartEntity, subtotal))
                .toList()
        );
        return Optional.of(response);
    }


    @Override
    @Transactional
    public ShoppingCartItem saveShoppingCart(String userId, ShoppingCartItemRequest shoppingCartItemRequest) {

        /*
        userId - carrito1
        shoppingCartRequest:{
            - products: [
                    ProductRequest1(id,quantity), -> buscar product entity por id ProductEntity, calcular el subtotal
                    ProductRequest2(id,quantity),
                    ProductRequest3(id,quantity),
              ]
        }


        ----------------------------------
        id 1 - zumos melocoton 2 EUR - 2 CANT = 4 EUR
        id 2 - jamon serrano   3 EUR - 1 CANT = 3 EUR


        ------------ subtotal:                  7 EUR
         */


        Optional<ShoppingCartItemEntity> shoppingCartItemResult = shoppingCartEntityRepository.findById(userId);

        ShoppingCartItemEntity shoppingCartItemEntity;
        if(shoppingCartItemResult.isEmpty()){
            shoppingCartItemEntity = new ShoppingCartItemEntity();
            shoppingCartItemEntity.setId(userId);
            shoppingCartItemEntity.setProducts(new ArrayList<>());
        }else{
            shoppingCartItemEntity = shoppingCartItemResult.get();
        }


        //shoppingCartItem o vacio pero creado o con valores de que se obtuvo de la consulta a la base de datos


        // se busca los productos que he enviado como request en la base de datos
        // se guarda unicamente lo necesario en productEntity
        List<ProductShoppingCartEntity> productEntitiesFound = shoppingCartItemRequest
                .getProducts()
                .stream()
                .map(productRequest -> {
                    final Optional<ProductEntity> productResult = productEntityRepository.findById(productRequest.getId());

                    if(productResult.isEmpty()){
                        throw new ProductNotFoundException("Product not found by id:" + productRequest.getId());
                    }

                    ProductShoppingCartEntity productShoppingCartEntity = new ProductShoppingCartEntity();

                    ProductEntity productEntity = productResult.get();

                    productShoppingCartEntity.setProductId(productEntity.getId());
                    productShoppingCartEntity.setQuantity(productRequest.getQuantity());
                    productShoppingCartEntity.setShoppingCartId(shoppingCartItemEntity.getId());

                    return productShoppingCartEntity;
                }).toList();



        shoppingCartItemEntity.getProducts().clear();
        shoppingCartItemEntity.getProducts().addAll(productEntitiesFound);

        // ahora viene el procesado de los subtotales y devolver la respuesta al usuario de todos los calculos realizados
        ShoppingCartItem response = new ShoppingCartItem();

        AtomicReference<BigDecimal> subtotal =  new AtomicReference<>();
        subtotal.set(BigDecimal.ZERO);

        response.setId(userId);
        response.setProducts(shoppingCartItemEntity
                .getProducts()
                .stream()
                .map(productShoppingCartEntity -> getProductFromEntity(productShoppingCartEntity, subtotal))
                .toList()
        );
        //TODO hay un error en el caso de que ya exista el carrito de compra
        response.setSubtotal(subtotal.get());
        shoppingCartEntityRepository.saveAndFlush(shoppingCartItemEntity);

        return response;
    }

    @Override
    public boolean deleteShoppingCartByUserId(String userId) {
        Optional<ShoppingCartItemEntity> shoppingCartEntityOptional = shoppingCartEntityRepository.findById(userId);

        if (shoppingCartEntityOptional.isPresent()) {
            ShoppingCartItemEntity shoppingCartEntity = shoppingCartEntityOptional.get();
            // Borra el carrito de compra de la base de datos
            shoppingCartEntityRepository.delete(shoppingCartEntity);
            return true;
        } else {
            return false; // El carrito de compra no se encontró para el usuario especificado
        }
    }

    @Override
    public InvoiceItem buyShoppingCart(String userId) {

        Optional<ShoppingCartItemEntity> shoppingCartItemEntityOptional = this.shoppingCartEntityRepository.findById(userId);

        if(shoppingCartItemEntityOptional.isEmpty()){
            throw new ShoppingCartNotFoundException();
        }
        ShoppingCartItemEntity shoppingCartItemEntity = shoppingCartItemEntityOptional.get();

        boolean shoppingCartIsNotEmpty = shoppingCartItemEntity
                .getProducts()
                .stream()
                .filter(Objects::nonNull)
                .allMatch(product-> Objects.nonNull(product.getQuantity()) && product.getQuantity().compareTo(BigDecimal.ZERO) > 0);

        if(!shoppingCartIsNotEmpty){
            throw new ShoppingCartInvalidProductsException();
        }















        //TODO guardar y restar los productos del inventario
        //si hay suficiente stock se procesa
        //si no hay stock debe eliminarlo del carrito y guardar,
        // ademas debe notificar al usuario de que no hay existencias de ese producto
        return null;
    }

    private Product getProductFromEntity(ProductShoppingCartEntity productShoppingCartEntity, AtomicReference<BigDecimal> subtotal) {
        BigDecimal subtotalProduct = BigDecimal.ZERO;

        Product product= new Product();

        product.setId(productShoppingCartEntity.getProductId());
        product.setQuantity(productShoppingCartEntity.getQuantity());

        final Optional<ProductEntity> productResult = productEntityRepository.findById(productShoppingCartEntity.getProductId());

        if(productResult.isEmpty()){
            throw new ProductNotFoundException("Product not found by id: "+ productShoppingCartEntity.getProductId());
        }

        final ProductEntity productEntity = productResult.get();

        product.setName(productEntity.getName());
        product.setGalleries(productEntity.getGalleries());
        product.setDescription(productEntity.getDescription());
        product.setPrice(productEntity.getPrice());
        product.setCategory(productEntity.getCategory());

        subtotalProduct = product.getPrice().multiply(product.getQuantity());

        product.setSubtotal(subtotalProduct);

        subtotal.set( subtotal.get().add( subtotalProduct  ));

        return product;
    }

}
