import { Icon, type IconProps } from "@iconify/react";
import activity from "@iconify-icons/lucide/activity";
import arrowLeft from "@iconify-icons/lucide/arrow-left";
import arrowRight from "@iconify-icons/lucide/arrow-right";
import waves from "@iconify-icons/lucide/waves";
import alertTriangle from "@iconify-icons/lucide/alert-triangle";
import bookOpenText from "@iconify-icons/lucide/book-open-text";
import bot from "@iconify-icons/lucide/bot";
import brain from "@iconify-icons/lucide/brain";
import briefcase from "@iconify-icons/lucide/briefcase";
import checkCircle2 from "@iconify-icons/lucide/check-circle-2";
import chevronDown from "@iconify-icons/lucide/chevron-down";
import coins from "@iconify-icons/lucide/coins";
import fileScan from "@iconify-icons/lucide/file-scan";
import fileClock from "@iconify-icons/lucide/file-clock";
import fileText from "@iconify-icons/lucide/file-text";
import gauge from "@iconify-icons/lucide/gauge";
import graduationCap from "@iconify-icons/lucide/graduation-cap";
import history from "@iconify-icons/lucide/history";
import keyRound from "@iconify-icons/lucide/key-round";
import landmark from "@iconify-icons/lucide/landmark";
import languages from "@iconify-icons/lucide/languages";
import listChecks from "@iconify-icons/lucide/list-checks";
import lockKeyhole from "@iconify-icons/lucide/lock-keyhole";
import logOut from "@iconify-icons/lucide/log-out";
import mail from "@iconify-icons/lucide/mail";
import mailCheck from "@iconify-icons/lucide/mail-check";
import mic from "@iconify-icons/lucide/mic";
import mic2 from "@iconify-icons/lucide/mic-2";
import penLine from "@iconify-icons/lucide/pen-line";
import play from "@iconify-icons/lucide/play";
import radio from "@iconify-icons/lucide/radio";
import radioTower from "@iconify-icons/lucide/radio-tower";
import receipt from "@iconify-icons/lucide/receipt";
import route from "@iconify-icons/lucide/route";
import rotateCcw from "@iconify-icons/lucide/rotate-ccw";
import settings2 from "@iconify-icons/lucide/settings-2";
import shieldCheck from "@iconify-icons/lucide/shield-check";
import sparkles from "@iconify-icons/lucide/sparkles";
import square from "@iconify-icons/lucide/square";
import subtitles from "@iconify-icons/lucide/subtitles";
import trash2 from "@iconify-icons/lucide/trash-2";
import users from "@iconify-icons/lucide/users";
import volume2 from "@iconify-icons/lucide/volume-2";
import type { IconifyIcon } from "@iconify/types";

const icons: Record<string, IconifyIcon> = {
  "lucide:activity": activity,
  "lucide:arrow-left": arrowLeft,
  "lucide:arrow-right": arrowRight,
  "lucide:audio-lines": waves,
  "lucide:alert-triangle": alertTriangle,
  "lucide:book-open-text": bookOpenText,
  "lucide:bot": bot,
  "lucide:brain": brain,
  "lucide:briefcase-business": briefcase,
  "lucide:captions": subtitles,
  "lucide:check-circle-2": checkCircle2,
  "lucide:chevron-down": chevronDown,
  "lucide:coins": coins,
  "lucide:file-clock": fileClock,
  "lucide:file-scan": fileScan,
  "lucide:file-text": fileText,
  "lucide:gauge": gauge,
  "lucide:graduation-cap": graduationCap,
  "lucide:history": history,
  "lucide:key-round": keyRound,
  "lucide:landmark": landmark,
  "lucide:languages": languages,
  "lucide:list-checks": listChecks,
  "lucide:lock-keyhole": lockKeyhole,
  "lucide:log-out": logOut,
  "lucide:mail": mail,
  "lucide:mail-check": mailCheck,
  "lucide:mic": mic,
  "lucide:mic-2": mic2,
  "lucide:notebook-pen": penLine,
  "lucide:notebook-tabs": bookOpenText,
  "lucide:play": play,
  "lucide:radio": radio,
  "lucide:radio-tower": radioTower,
  "lucide:receipt-text": receipt,
  "lucide:route": route,
  "lucide:rotate-ccw": rotateCcw,
  "lucide:settings-2": settings2,
  "lucide:shield-check": shieldCheck,
  "lucide:sparkles": sparkles,
  "lucide:square": square,
  "lucide:trash-2": trash2,
  "lucide:triangle-alert": alertTriangle,
  "lucide:users": users,
  "lucide:volume-2": volume2,
  "lucide:waveform": waves,
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
