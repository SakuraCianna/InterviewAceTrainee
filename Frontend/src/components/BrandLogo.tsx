type BrandLogoProps = {
  size?: number;
  className?: string;
};

export function BrandLogo({ size = 28, className = "" }: BrandLogoProps) {
  return (
    <img
      alt=""
      aria-hidden="true"
      className={`brand-logo${className ? ` ${className}` : ""}`}
      height={size}
      src="/mianba-logo.svg"
      width={size}
    />
  );
}
