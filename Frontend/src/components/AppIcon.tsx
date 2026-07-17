import { Icon, type IconProps } from "@iconify/react";
import activity from "@iconify-icons/lucide/activity";
import arrowLeft from "@iconify-icons/lucide/arrow-left";
import arrowRight from "@iconify-icons/lucide/arrow-right";
import waves from "@iconify-icons/lucide/waves";
import alertTriangle from "@iconify-icons/lucide/alert-triangle";
import barChart2 from "@iconify-icons/lucide/bar-chart-2";
import bookOpenText from "@iconify-icons/lucide/book-open-text";
import bot from "@iconify-icons/lucide/bot";
import brain from "@iconify-icons/lucide/brain";
import briefcase from "@iconify-icons/lucide/briefcase";
import checkCircle2 from "@iconify-icons/lucide/check-circle-2";
import chevronDown from "@iconify-icons/lucide/chevron-down";
import clipboardCheck from "@iconify-icons/lucide/clipboard-check";
import coins from "@iconify-icons/lucide/coins";
import fileCheck2 from "@iconify-icons/lucide/file-check-2";
import fileScan from "@iconify-icons/lucide/file-scan";
import fileClock from "@iconify-icons/lucide/file-clock";
import fileText from "@iconify-icons/lucide/file-text";
import gauge from "@iconify-icons/lucide/gauge";
import graduationCap from "@iconify-icons/lucide/graduation-cap";
import headphones from "@iconify-icons/lucide/headphones";
import history from "@iconify-icons/lucide/history";
import info from "@iconify-icons/lucide/info";
import keyRound from "@iconify-icons/lucide/key-round";
import landmark from "@iconify-icons/lucide/landmark";
import languages from "@iconify-icons/lucide/languages";
import layoutDashboard from "@iconify-icons/lucide/layout-dashboard";
import listChecks from "@iconify-icons/lucide/list-checks";
import lockKeyhole from "@iconify-icons/lucide/lock-keyhole";
import logOut from "@iconify-icons/lucide/log-out";
import mail from "@iconify-icons/lucide/mail";
import mailCheck from "@iconify-icons/lucide/mail-check";
import mic from "@iconify-icons/lucide/mic";
import mic2 from "@iconify-icons/lucide/mic-2";
import panelRightOpen from "@iconify-icons/lucide/panel-right-open";
import penLine from "@iconify-icons/lucide/pen-line";
import play from "@iconify-icons/lucide/play";
import radio from "@iconify-icons/lucide/radio";
import radioTower from "@iconify-icons/lucide/radio-tower";
import receipt from "@iconify-icons/lucide/receipt";
import route from "@iconify-icons/lucide/route";
import rotateCcw from "@iconify-icons/lucide/rotate-ccw";
import server from "@iconify-icons/lucide/server";
import settings2 from "@iconify-icons/lucide/settings-2";
import shieldCheck from "@iconify-icons/lucide/shield-check";
import sparkles from "@iconify-icons/lucide/sparkles";
import square from "@iconify-icons/lucide/square";
import subtitles from "@iconify-icons/lucide/subtitles";
import trash2 from "@iconify-icons/lucide/trash-2";
import trendingUp from "@iconify-icons/lucide/trending-up";
import users from "@iconify-icons/lucide/users";
import volume2 from "@iconify-icons/lucide/volume-2";
import zap from "@iconify-icons/lucide/zap";
import x from "@iconify-icons/lucide/x";
import chevronRight from "@iconify-icons/lucide/chevron-right";
import search from "@iconify-icons/lucide/search";
import shieldBan from "@iconify-icons/lucide/shield-ban";
import shieldAlert from "@iconify-icons/lucide/shield-alert";
import shieldQuestion from "@iconify-icons/lucide/shield-question";
import serverCog from "@iconify-icons/lucide/server-cog";
import type { IconifyIcon } from "@iconify/types";

const icons: Record<string, IconifyIcon> = {
  "lucide:activity": activity,
  "lucide:arrow-left": arrowLeft,
  "lucide:arrow-right": arrowRight,
  "lucide:audio-lines": waves,
  "lucide:alert-triangle": alertTriangle,
  "lucide:bar-chart-2": barChart2,
  "lucide:book-open-text": bookOpenText,
  "lucide:bot": bot,
  "lucide:bot-message-square": bot,
  "lucide:brain": brain,
  "lucide:briefcase-business": briefcase,
  "lucide:captions": subtitles,
  "lucide:check-circle-2": checkCircle2,
  "lucide:chevron-down": chevronDown,
  "lucide:clipboard-check": clipboardCheck,
  "lucide:coins": coins,
  "lucide:file-check-2": fileCheck2,
  "lucide:file-clock": fileClock,
  "lucide:file-scan": fileScan,
  "lucide:file-text": fileText,
  "lucide:gauge": gauge,
  "lucide:graduation-cap": graduationCap,
  "lucide:headphones": headphones,
  "lucide:history": history,
  "lucide:info": info,
  "lucide:key-round": keyRound,
  "lucide:landmark": landmark,
  "lucide:languages": languages,
  "lucide:layout-dashboard": layoutDashboard,
  "lucide:list-checks": listChecks,
  "lucide:lock-keyhole": lockKeyhole,
  "lucide:log-out": logOut,
  "lucide:mail": mail,
  "lucide:mail-check": mailCheck,
  "lucide:mic": mic,
  "lucide:mic-2": mic2,
  "lucide:notebook-pen": penLine,
  "lucide:notebook-tabs": bookOpenText,
  "lucide:panel-right-open": panelRightOpen,
  "lucide:play": play,
  "lucide:radio": radio,
  "lucide:radio-tower": radioTower,
  "lucide:receipt-text": receipt,
  "lucide:route": route,
  "lucide:rotate-ccw": rotateCcw,
  "lucide:server": server,
  "lucide:settings-2": settings2,
  "lucide:shield-check": shieldCheck,
  "lucide:sparkles": sparkles,
  "lucide:square": square,
  "lucide:trash-2": trash2,
  "lucide:triangle-alert": alertTriangle,
  "lucide:trending-up": trendingUp,
  "lucide:users": users,
  "lucide:volume-2": volume2,
  "lucide:waveform": waves,
  "lucide:zap": zap,
  "lucide:x": x,
  "lucide:chevron-right": chevronRight,
  "lucide:search": search,
  "lucide:shield-ban": shieldBan,
  "lucide:shield-alert": shieldAlert,
  "lucide:shield-question": shieldQuestion,
  "lucide:bot-off": bot,
  "lucide:server-cog": serverCog,
  "lucide:circle-check-big": checkCircle2,
  "solar:soundwave-circle-bold-duotone": waves,
};

type AppIconProps = Omit<IconProps, "icon"> & {
  icon: string;
  size?: number;
  title?: string;
};

export function AppIcon({ icon, size = 20, title, className, ...props }: AppIconProps) {
  return (
    <Icon
      aria-hidden={title ? undefined : true}
      aria-label={title}
      className={`app-icon${className ? ` ${className}` : ""}`}
      height={size}
      icon={icons[icon] ?? icon}
      width={size}
      {...props}
    />
  );
}
