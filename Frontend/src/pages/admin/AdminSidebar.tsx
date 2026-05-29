import { AppIcon } from "../../components/AppIcon";
import { BrandLogo } from "../../components/BrandLogo";
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
  return (
    <aside className="admin-sidebar" aria-label="管理员后台侧栏">
      <a href="/" className="admin-sidebar-brand">
        <BrandLogo size={30} />
        <span>面霸练习生</span>
      </a>
      <nav className="admin-sidebar-nav" aria-label="后台导航">
        {navItems.map((item) => (
          <button
            type="button"
            className={activeSection === item.key ? "is-active" : ""}
            key={item.key}
            onClick={() => onSelectSection(item.key)}
          >
            <AppIcon icon={item.icon} size={20} />
            <span>{item.label}</span>
          </button>
        ))}
      </nav>
      <div className="admin-sidebar-status" aria-live="polite">
        <span>当前账号</span>
        <strong>{currentUser.email}</strong>
        <p>{statusMessage}</p>
      </div>
      <div className="admin-sidebar-actions">
        <button type="button" onClick={onRefresh}>
          <AppIcon icon="lucide:refresh-cw" size={18} />
          刷新数据
        </button>
        <button type="button" className="admin-sidebar-logout" onClick={onLogout}>
          <AppIcon icon="lucide:log-out" size={18} />
          退出
        </button>
      </div>
    </aside>
  );
}
