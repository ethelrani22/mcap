
package nic.meg.mcap.controllers.pageControllers;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import nic.meg.mcap.dto.request.UserDTO;
import nic.meg.mcap.dto.response.RoleResponseDTO;
import nic.meg.mcap.dto.response.UserResponseByCode;
import nic.meg.mcap.entities.User;
import nic.meg.mcap.enums.OrgOwnerType;
import nic.meg.mcap.services.InstituteDepartmentService;
import nic.meg.mcap.services.InstituteService;
import nic.meg.mcap.services.RoleService;
import nic.meg.mcap.services.UserService;

@Controller
@PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
@RequestMapping("/user-management")

public class UserPageController {
	@Autowired
	private UserService userService;

	@Autowired
	private RoleService roleService;

	@Autowired
	private InstituteService instituteService;

	@Autowired
	private InstituteDepartmentService instituteDeptService;

	@GetMapping("/dashboard")
	public String userDashboard(Model model) {
		model.addAttribute("activePage", "User Dashboard");
		return "user-management/dashboard";
	}

	@GetMapping("/create-user")
	public String createUser(Model model, Principal principal) {

		model.addAttribute("activePage", "Create User");
		model.addAttribute("userDTO", new UserDTO());

		User loggedInUser = (User) userService.loadUserByUsername(principal.getName());

		List<RoleResponseDTO> roles;
		List<OrgOwnerType> ownerTypes;

		if ("INSTITUTE".equals(loggedInUser.getRole().getRoleName())) {

			// Institute users can create only Institute users
			roles = roleService.getAllRoles().stream().filter(role -> "INSTITUTE".equals(role.getRoleName())).toList();

			// Institute users can own only these organization types
			ownerTypes = List.of(OrgOwnerType.INSTITUTE, OrgOwnerType.INSTITUTE_DEPARTMENT);

		} else {

			// Other users
			roles = roleService.getAllRoles();
			ownerTypes = Arrays.asList(OrgOwnerType.values());
		}

		model.addAttribute("roles", roles);
		model.addAttribute("ownerTypes", ownerTypes);

		Short instituteId = instituteService.getInstituteIdByUsername(principal.getName());

		if (instituteId != null) {
			model.addAttribute("departments", instituteDeptService.getActiveDepartmentsByInstitute(instituteId));
		}

		return "user-management/create-user";
	}

	// Get Users Page
	@GetMapping("/get-users")
	public String getListUser(Model model) {
		model.addAttribute("activePage", "Manage Users");
		return "user-management/get-users";
	}

	@GetMapping("/profile-dashboard")
	public String profileDashboard(Model model) {
		model.addAttribute("activePage", "Profile Dashboard"); // matches DB menu_name for highlight
		return "profile/profile-dashboard"; // folder and file under templates/
	}

	@GetMapping("/user-profile")
	public String userProfilePage(Model model) {
		UserResponseByCode currentUser = userService.getUserByUsername();
		model.addAttribute("userCode", currentUser.getUserCode());
		return "profile/user-profile";
	}

	@GetMapping("/update-profile")
	public String updateProfilePage(Model model) {
		UserResponseByCode currentUser = userService.getUserByUsername();
		model.addAttribute("userCode", currentUser.getUserCode().toString());
		return "profile/update-profile";
	}

	@GetMapping("/change-password")
	public String changePassword(Model model) {
		model.addAttribute("activePage", "Profile Dashboard");
		return "profile/change-password";
	}

	// Login Activities Page
	@GetMapping("/login-activities")
	public String loginActivities(Model model) {
		model.addAttribute("activePage", "Login Activities");
		return "user-management/login-activities";
	}

}
