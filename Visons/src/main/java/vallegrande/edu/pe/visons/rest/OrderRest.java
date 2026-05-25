package vallegrande.edu.pe.visons.rest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import vallegrande.edu.pe.visons.dto.OrderDTO;
import vallegrande.edu.pe.visons.dto.OrderDetailDTO;
import vallegrande.edu.pe.visons.dto.OrderResponseDTO;
import vallegrande.edu.pe.visons.dto.UserResponse;
import vallegrande.edu.pe.visons.model.Customer;
import vallegrande.edu.pe.visons.model.Order;
import vallegrande.edu.pe.visons.repository.CustomerRepository;
import vallegrande.edu.pe.visons.repository.OrderRepository;
import vallegrande.edu.pe.visons.service.OrderPdfService;
import vallegrande.edu.pe.visons.service.UserService;

@RestController
@RequestMapping("/api/orders")
public class OrderRest {

    private static final String STATUS_PENDING = "Pending";
    private static final String STATUS_PROCESSING = "Processing";
    private static final String STATUS_COMPLETED = "Completed";
    private static final String STATUS_CANCELLED = "Cancelled";

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderPdfService orderPdfService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserService userService;

    @GetMapping
    public List<OrderResponseDTO> getAllOrders() {
        List<Order> orders = orderRepository.findAll(Sort.by(Sort.Direction.DESC, "orderDate"));
        return orders.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @GetMapping("/my")
    public List<OrderResponseDTO> getMyOrders(HttpSession session) {
        List<Order> accessibleOrders = resolveAccessibleOrders(session);
        return accessibleOrders.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @GetMapping("/pending")
    public List<OrderResponseDTO> getPendingOrders(HttpSession session) {
        return resolveAccessibleOrders(session).stream()
                .filter(order -> STATUS_PENDING.equals(normalizeStatus(order.getStatus())))
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponseDTO> getOrderById(@PathVariable Integer id, HttpSession session) {
        Order order = findAccessibleOrder(id, session);
        return ResponseEntity.ok(toResponse(order));
    }

    @PatchMapping("/{id}/accept")
    public ResponseEntity<OrderResponseDTO> acceptOrder(@PathVariable Integer id, HttpSession session) {
        Order updated = updateOrderStatus(id, session, STATUS_PROCESSING);
        return ResponseEntity.ok(toResponse(updated));
    }

    @PatchMapping("/{id}/reject")
    public ResponseEntity<OrderResponseDTO> rejectOrder(@PathVariable Integer id, HttpSession session) {
        Order updated = updateOrderStatus(id, session, STATUS_CANCELLED);
        return ResponseEntity.ok(toResponse(updated));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> createOrder(@Valid @RequestBody OrderDTO orderDTO) {
        try {
            if (orderDTO == null || orderDTO.getClientId() == null) {
                throw new RuntimeException("clientId es NULL");
            }

            Optional<Customer> customerOpt = customerRepository.findById(orderDTO.getClientId());
            if (customerOpt.isEmpty()) {
                throw new RuntimeException("Cliente con ID " + orderDTO.getClientId() + " NO existe");
            }

            if (orderDTO.getOrderCode() == null || orderDTO.getOrderCode().isEmpty()) {
                throw new RuntimeException("orderCode no puede estar vacío");
            }
            if (orderDTO.getOrderDate() == null) {
                throw new RuntimeException("orderDate no puede ser NULL");
            }

            if (orderRepository.findByOrderCodeIgnoreCase(orderDTO.getOrderCode().trim()).isPresent()) {
                throw new RuntimeException("El orderCode ya existe");
            }

            Order order = new Order();
            order.setCustomer(customerOpt.get());
            order.setOrderCode(orderDTO.getOrderCode());
            order.setOrderDate(orderDTO.getOrderDate());
            order.setIncoterm(orderDTO.getIncoterm());
            order.setStatus(normalizeStatus(orderDTO.getStatus()));

            Order savedOrder = orderRepository.save(order);
            saveOrderDetails(savedOrder.getOrderId(), orderDTO.getOrderDetails(), consumesInventory(savedOrder.getStatus()));

            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(savedOrder));
        } catch (Exception e) {
            throw e;
        }
    }

    @PatchMapping("/update/{id}")
    @Transactional
    public ResponseEntity<?> updateOrder(@PathVariable Integer id, @Valid @RequestBody OrderDTO orderDTO) {
        try {
            Optional<Order> orderOpt = orderRepository.findById(id);
            if (orderOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Pedido no encontrado");
            }

            Optional<Customer> customerOpt = customerRepository.findById(orderDTO.getClientId());
            if (customerOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Cliente no existe");
            }

            if (orderDTO.getOrderCode() != null && !orderDTO.getOrderCode().isBlank()) {
                orderRepository.findByOrderCodeIgnoreCase(orderDTO.getOrderCode().trim())
                        .filter(existing -> !existing.getOrderId().equals(id))
                        .ifPresent(existing -> {
                            throw new RuntimeException("El orderCode ya existe");
                        });
            }

            Order order = orderOpt.get();
            boolean consumedInventoryBefore = consumesInventory(order.getStatus());
            order.setCustomer(customerOpt.get());
            order.setOrderCode(orderDTO.getOrderCode());
            order.setOrderDate(orderDTO.getOrderDate());
            order.setIncoterm(orderDTO.getIncoterm());
            order.setStatus(normalizeStatus(orderDTO.getStatus()));

            Order savedOrder = orderRepository.save(order);
            replaceOrderDetails(
                    savedOrder.getOrderId(),
                    orderDTO.getOrderDetails(),
                    consumedInventoryBefore,
                    consumesInventory(savedOrder.getStatus()));
            return ResponseEntity.ok(toResponse(savedOrder));
        } catch (Exception e) {
            throw e;
        }
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deleteOrder(@PathVariable Integer id) {
        try {
            Optional<Order> orderOpt = orderRepository.findById(id);
            if (orderOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Pedido no encontrado");
            }

            if (consumesInventory(orderOpt.get().getStatus())) {
                restoreOrderStock(id);
            }
            jdbcTemplate.update("DELETE FROM ORDER_DETAILS WHERE order_id = ?", id);
            orderRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> downloadOrderPdf(@PathVariable Integer id, HttpSession session) throws Exception {
        findAccessibleOrder(id, session);
        byte[] pdf = orderPdfService.generateOrderPdf(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "order_" + id + ".pdf");
        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }

    @GetMapping("/report/pdf")
    public ResponseEntity<byte[]> downloadOrdersReportPdf() throws Exception {
        byte[] pdf = orderPdfService.generateOrdersReportPdf();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "orders_report.pdf");
        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }

    private Order findAccessibleOrder(Integer orderId, HttpSession session) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));

        UserResponse currentUser = userService.currentSessionUser(session);
        if (isClient(currentUser) && currentUser.getClientId() != null) {
            Integer orderClientId = order.getCustomer() != null ? order.getCustomer().getClientId() : null;
            if (orderClientId == null || !orderClientId.equals(currentUser.getClientId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes permiso para ver este pedido");
            }
        }

        return order;
    }

    private Order updateOrderStatus(Integer orderId, HttpSession session, String newStatus) {
        UserResponse currentUser = userService.currentSessionUser(session);
        if (isClient(currentUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes permiso para procesar pedidos");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));
        boolean consumedInventoryBefore = consumesInventory(order.getStatus());
        boolean consumesInventoryAfter = consumesInventory(newStatus);
        if (consumedInventoryBefore && !consumesInventoryAfter) {
            restoreOrderStock(orderId);
        } else if (!consumedInventoryBefore && consumesInventoryAfter) {
            consumeOrderStock(orderId);
        }

        order.setStatus(newStatus);
        return orderRepository.save(order);
    }

    private List<Order> resolveAccessibleOrders(HttpSession session) {
        UserResponse currentUser = userService.currentSessionUser(session);
        if (!isClient(currentUser) || currentUser.getClientId() == null) {
            return orderRepository.findAll(Sort.by(Sort.Direction.DESC, "orderDate"));
        }
        return orderRepository.findByCustomer_ClientIdOrderByOrderDateDesc(currentUser.getClientId());
    }

    private OrderResponseDTO toResponse(Order order) {
        return new OrderResponseDTO(
                order.getOrderId(),
                order.getCustomer().getClientId(),
                order.getCustomer().getCompanyName(),
                order.getOrderCode(),
                order.getOrderDate(),
                order.getIncoterm(),
                normalizeStatus(order.getStatus()),
                findOrderDetails(order.getOrderId()));
    }

    private void replaceOrderDetails(
            Integer orderId,
            List<OrderDetailDTO> details,
            boolean consumedInventoryBefore,
            boolean consumesInventoryAfter) {
        if (consumedInventoryBefore) {
            restoreOrderStock(orderId);
        }
        jdbcTemplate.update("DELETE FROM ORDER_DETAILS WHERE order_id = ?", orderId);

        if (details == null || details.isEmpty()) {
            return;
        }

        saveOrderDetails(orderId, details, consumesInventoryAfter);
    }

    private void saveOrderDetails(Integer orderId, List<OrderDetailDTO> details, boolean consumeInventory) {
        if (details == null || details.isEmpty()) {
            return;
        }

        for (OrderDetailDTO detail : details) {
            if (detail == null || detail.getProductId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "productId es requerido en el detalle");
            }

            BigDecimal quantityKg = detail.getQuantityKg();
            if (quantityKg == null || quantityKg.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantityKg debe ser mayor que 0");
            }

            BigDecimal unitPrice = detail.getUnitPrice() == null ? BigDecimal.ZERO : detail.getUnitPrice();
            if (unitPrice.compareTo(BigDecimal.ZERO) < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unitPrice debe ser mayor o igual a 0");
            }

            String productName = findProductName(detail.getProductId());
            if (consumeInventory) {
                decreaseProductStock(detail.getProductId(), productName, quantityKg);
            }

            jdbcTemplate.update(
                    "INSERT INTO ORDER_DETAILS (order_id, product_id, quantity_kg, unit_price) VALUES (?, ?, ?, ?)",
                    orderId,
                    detail.getProductId(),
                    quantityKg,
                    unitPrice);
        }
    }

    private void consumeOrderStock(Integer orderId) {
        for (InventoryLine detail : findInventoryLinesByOrderId(orderId)) {
            decreaseProductStock(detail.productId, detail.productName, detail.quantityKg);
        }
    }

    private void restoreOrderStock(Integer orderId) {
        for (InventoryLine detail : findInventoryLinesByOrderId(orderId)) {
            increaseProductStock(detail.productId, detail.quantityKg);
        }
    }

    private List<InventoryLine> findInventoryLinesByOrderId(Integer orderId) {
        String sql = "SELECT od.product_id, p.name AS product_name, od.quantity_kg "
                + "FROM ORDER_DETAILS od JOIN PRODUCTS p ON od.product_id = p.product_id "
                + "WHERE od.order_id = ?";

        return jdbcTemplate.query(sql, new Object[] { orderId }, (rs, rowNum) -> new InventoryLine(
                rs.getInt("product_id"),
                rs.getString("product_name"),
                rs.getBigDecimal("quantity_kg")));
    }

    private String findProductName(Integer productId) {
        List<String> names = jdbcTemplate.query(
                "SELECT name FROM PRODUCTS WHERE product_id = ?",
                new Object[] { productId },
                (rs, rowNum) -> rs.getString("name"));

        if (names.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Producto con ID " + productId + " no existe");
        }

        return names.get(0);
    }

    private void decreaseProductStock(Integer productId, String productName, BigDecimal quantityKg) {
        int updated = jdbcTemplate.update(
                "UPDATE CURRENT_INVENTORY "
                        + "SET total_stock_kg = total_stock_kg - ?, "
                        + "available_stock_kg = available_stock_kg - ? "
                        + "WHERE product_id = ? "
                        + "AND total_stock_kg >= ? "
                        + "AND available_stock_kg >= ?",
                quantityKg,
                quantityKg,
                productId,
                quantityKg,
                quantityKg);

        if (updated > 0) {
            return;
        }

        Integer inventoryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM CURRENT_INVENTORY WHERE product_id = ?",
                Integer.class,
                productId);

        if (inventoryCount == null || inventoryCount == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No existe inventario para el producto " + productName);
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Stock insuficiente para el producto " + productName);
    }

    private void increaseProductStock(Integer productId, BigDecimal quantityKg) {
        int updated = jdbcTemplate.update(
                "UPDATE CURRENT_INVENTORY "
                        + "SET total_stock_kg = total_stock_kg + ?, "
                        + "available_stock_kg = available_stock_kg + ? "
                        + "WHERE product_id = ?",
                quantityKg,
                quantityKg,
                productId);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No existe inventario para el producto " + productId);
        }
    }

    private List<OrderDetailDTO> findOrderDetails(Integer orderId) {
        try {
            String sql = "SELECT od.product_id, p.name AS product_name, od.quantity_kg, od.unit_price, "
                    + "(od.quantity_kg * od.unit_price) AS line_total "
                    + "FROM ORDER_DETAILS od JOIN PRODUCTS p ON od.product_id = p.product_id "
                    + "WHERE od.order_id = ?";

            return jdbcTemplate.query(sql, new Object[] { orderId }, (rs, rowNum) -> new OrderDetailDTO(
                    rs.getInt("product_id"),
                    rs.getString("product_name"),
                    rs.getBigDecimal("quantity_kg"),
                    rs.getBigDecimal("unit_price"),
                    rs.getBigDecimal("line_total")));
        } catch (DataAccessException ex) {
            return new ArrayList<>();
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return STATUS_PENDING;
        }

        return switch (status.trim().toLowerCase()) {
            case "pending", "pendiente" -> STATUS_PENDING;
            case "processing", "procesado", "procesando", "en proceso" -> STATUS_PROCESSING;
            case "completed", "complete", "completado", "entregado" -> STATUS_COMPLETED;
            case "cancelled", "canceled", "cancelado", "denegado", "rechazado" -> STATUS_CANCELLED;
            default -> status.trim();
        };
    }

    private boolean isClient(UserResponse user) {
        String role = user == null ? null : user.getUserTypeName();
        return role != null && role.equalsIgnoreCase("CLIENT");
    }

    private boolean consumesInventory(String status) {
        return !STATUS_CANCELLED.equals(normalizeStatus(status));
    }

    private static final class InventoryLine {
        private final Integer productId;
        private final String productName;
        private final BigDecimal quantityKg;

        private InventoryLine(Integer productId, String productName, BigDecimal quantityKg) {
            this.productId = productId;
            this.productName = productName;
            this.quantityKg = quantityKg;
        }
    }
}
