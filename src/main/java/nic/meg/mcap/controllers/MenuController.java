package nic.meg.mcap.controllers;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// Import for logging
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import nic.meg.mcap.dto.response.MenuResponseDTO;
import nic.meg.mcap.entities.User;
import nic.meg.mcap.enums.OrgOwnerType;
import nic.meg.mcap.repositories.UserRepository;
import nic.meg.mcap.services.MenuService;

@RestController
public class MenuController {

	// Logger instance
	private static final Logger logger = LoggerFactory.getLogger(MenuController.class);

	private final MenuService menuService;
	private final UserRepository userRepository;

	public MenuController(MenuService menuService, UserRepository userRepository) {
		this.menuService = menuService;
		this.userRepository = userRepository;
	}

	/**
	 * Returns menu items for the currently logged-in user as JSON.
	 */
	@PreAuthorize("isAuthenticated()")
	@GetMapping(value = "/menu", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<MenuResponseDTO> getMenuForCurrentUser(Principal principal) {

		if (principal == null) {
			return List.of();
		}

		User user = userRepository.findByUsername(principal.getName()).orElse(null);

		if (user == null || user.getRole() == null) {
			return List.of();
		}

		String roleName = user.getRole().getRoleName();
		List<MenuResponseDTO> menu = menuService.getMenuForRole(roleName);

		// Restrict menu for Institute Department users
		if (user.getOrgOwnerType() == OrgOwnerType.INSTITUTE_DEPARTMENT) {

			Set<String> allowedMenus = Set.of("Account", "Profile", "Change Password", "Admission Management",
					"Manage Seats", "View Applications", "Admission Reports", "Allotment Verification");

			 menu = menu.stream()
			            .filter(m -> allowedMenus.contains(m.getName()))
			            .peek(m -> {
			                if (m.getChildren() != null) {
			                    m.setChildren(
			                            m.getChildren().stream()
			                                    .filter(c -> allowedMenus.contains(c.getName()))
			                                    .collect(Collectors.toList())
			                    );
			                }
			            })
			            .collect(Collectors.toList());
		}

		return menu;
	}
}