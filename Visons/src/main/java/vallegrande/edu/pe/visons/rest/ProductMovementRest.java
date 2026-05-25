package vallegrande.edu.pe.visons.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import vallegrande.edu.pe.visons.dto.ProductMovementRequestDTO;
import vallegrande.edu.pe.visons.dto.ProductMovementResponseDTO;
import vallegrande.edu.pe.visons.service.ProductMovementService;

@RestController
@Validated
@RequestMapping("/v1/api/product-movements")
public class ProductMovementRest {

    private final ProductMovementService productMovementService;

    public ProductMovementRest(ProductMovementService productMovementService) {
        this.productMovementService = productMovementService;
    }

    @PostMapping
    public ResponseEntity<ProductMovementResponseDTO> registerMovement(@Valid @RequestBody ProductMovementRequestDTO request) {
        ProductMovementResponseDTO response = productMovementService.registerMovement(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
