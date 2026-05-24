import { Icon, type IconProps } from "@iconify/react";
import activity from "@iconify-icons/lucide/activity";
import arrowLeft from "@iconify-icons/lucide/arrow-left";
import arrowRight from "@iconify-icons/lucide/arrow-right";
import waves from "@iconify-icons/lucide/waves";
import bot from "@iconify-icons/lucide/bot";
import brain from "@iconify-icons/lucide/brain";
import briefcase from "@iconify-icons/lucide/briefcase";
import checkCircle2 from "@iconify-icons/lucide/check-circle-2";
import chevronDown from "@iconify-icons/lucide/chevron-down";
import coins from "@iconify-icons/lucide/coins";
import database from "@iconify-icons/lucide/database";
import fileClock from "@iconify-icons/lucide/file-clock";
import fileText from "@iconify-icons/lucide/file-text";
import graduationCap from "@iconify-icons/lucide/graduation-cap";
import history from "@iconify-icons/lucide/history";
import keyRound from "@iconify-icons/lucide/key-round";
import landmark from "@iconify-icons/lucide/landmark";
import languages from "@iconify-icons/lucide/languages";
import mail from "@iconify-icons/lucide/mail";
import mailCheck from "@iconify-icons/lucide/mail-check";
import mic from "@iconify-icons/lucide/mic";
import mic2 from "@iconify-icons/lucide/mic-2";
import play from "@iconify-icons/lucide/play";
import radio from "@iconify-icons/lucide/radio";
import route from "@iconify-icons/lucide/route";
import rotateCcw from "@iconify-icons/lucide/rotate-ccw";
import settings from "@iconify-icons/lucide/settings";
import shieldCheck from "@iconify-icons/lucide/shield-check";
import sparkles from "@iconify-icons/lucide/sparkles";
import square from "@iconify-icons/lucide/square";
import users from "@iconify-icons/lucide/users";
import volume2 from "@iconify-icons/lucide/volume-2";
import type { IconifyIcon } from "@iconify/types";

const icons: Record<string, IconifyIcon> = {
  "lucide:activity": activity,
  "lucide:arrow-left": arrowLeft,
  "lucide:arrow-right": arrowRight,
  "lucide:audio-lines": waves,
  "lucide:audio-waveform": waves,
  "lucide:bot": bot,
  "lucide:brain": brain,
  "lucide:briefcase-business": briefcase,
  "lucide:check-circle-2": checkCircle2,
  "lucide:chevron-down": chevronDown,
  "lucide:coins": coins,
  "lucide:database": database,
  "lucide:file-clock": fileClock,
  "lucide:file-text": fileText,
  "lucide:graduation-cap": graduationCap,
  "lucide:history": history,
  "lucide:key-round": keyRound,
  "lucide:landmark": landmark,
  "lucide:languages": languages,
  "lucide:mail": mail,
  "lucide:mail-check": mailCheck,
  "lucide:mic": mic,
  "lucide:mic-2": mic2,
  "lucide:play": play,
  "lucide:radio": radio,
  "lucide:route": route,
  "lucide:rotate-ccw": rotateCcw,
  "lucide:settings": settings,
  "lucide:shield-check": shieldCheck,
  "lucide:sparkles": sparkles,
  "lucide:square": square,
  "lucide:users": users,
  "lucide:volume-2": volume2,
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
