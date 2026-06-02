import { type ReactNode, useLayoutEffect, useRef } from "react";
import { useLocation } from "react-router-dom";
import gsap from "gsap";

type RouteMotionProps = {
  children: ReactNode;
};

function prefersReducedMotion() {
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

export function RouteMotion({ children }: RouteMotionProps) {
  const location = useLocation();
  const frameRef = useRef<HTMLDivElement | null>(null);

  useLayoutEffect(() => {
    if (!frameRef.current || prefersReducedMotion()) {
      return;
    }

    const ctx = gsap.context(() => {
      gsap.fromTo(
        frameRef.current,
        { y: 12, filter: "blur(4px)" },
        { y: 0, filter: "blur(0px)", duration: 0.42, ease: "power3.out", clearProps: "transform,filter" },
      );
    }, frameRef);

    return () => ctx.revert();
  }, [location.pathname]);

  return (
    <div className="route-motion-frame" ref={frameRef}>
      {children}
    </div>
  );
}
