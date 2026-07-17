import { AppIcon } from "../../components/AppIcon";
import { BrandLogo } from "../../components/BrandLogo";
import { adminClasses } from "./adminStyles";
import type { AdminSectionKey, CurrentUser } from "./types";

export type AdminSidebarNavItem = {
  key: AdminSectionKey;
  label: string;
  icon: string;
};

type AdminSidebarProps = {
  activeSection: AdminSectionKey;
  currentUser: CurrentUser;
  navItems: AdminSidebarNavItem[];
  statusMessage: string;
  onSelectSection: (section: AdminSectionKey) => void;
  onRefresh: () => void;
  onLogout: () => void;
};

export function AdminSidebar({
  activeSection,
  currentUser,
  navItems,
  statusMessage,
  onSelectSection,
  onRefresh,
  onLogout,
}: AdminSidebarProps) {
  const avatarLetter = (currentUser.email?.[0] ?? "A").toUpperCase();
  return (
    <aside className={adminClasses("admin-sidebar")} aria-label="管理员后台侧栏">
      <a href="/" className={adminClasses("admin-sidebar-brand")}>
        <BrandLogo size={28} />
        <div>
          <span>面霸练习生</span>
          <em>管理控制台</em>
        </div>
      </a>

      <nav className={adminClasses("admin-sidebar-nav")} aria-label="后台导航">
        {navItems.map((item) => (
          <button
            type="button"
            className={adminClasses(activeSection === item.key && "is-active")}
            key={item.key}
            onClick={() => onSelectSection(item.key)}
            title={item.label}
          >
            <span className={adminClasses("admin-sidebar-nav-icon")}>
              <AppIcon icon={item.icon} size={20} />
            </span>
            <span>{item.label}</span>
          </button>
        ))}
      </nav>

      <div className={adminClasses("admin-sidebar-spacer")} aria-hidden="true" />

      <div className={adminClasses("admin-sidebar-user")} aria-live="polite">
        <div className={adminClasses("admin-sidebar-avatar")} aria-hidden="true">
          {avatarLetter}
        </div>
        <div className={adminClasses("admin-sidebar-user-info")}>
          <strong>{currentUser.email}</strong>
          <span>{statusMessage}</span>
        </div>
      </div>

      <div className={adminClasses("admin-sidebar-actions")}>
        <button type="button" onClick={onRefresh} title="刷新数据">
          <AppIcon icon="material-symbols:refresh-rounded" size={18} />
          刷新数据
        </button>
        <button type="button" className={adminClasses("admin-sidebar-logout")} onClick={onLogout} title="退出">
          <AppIcon icon="material-symbols:logout-rounded" size={18} />
          退出
        </button>
      </div>
    </aside>
  );
}
