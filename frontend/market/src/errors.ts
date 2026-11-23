export function messageOf(cause: unknown) {
  if (cause instanceof Error) {
    const translations: Record<string, string> = {
      'username or password is invalid': '用户名或密码不正确',
      'password must be at least 8 characters and at most 72 UTF-8 bytes': '密码至少 8 个字符、最多 72 字节，中文通常每字占 3 字节',
      'username is already registered': '这个用户名已经被注册',
      'marketplace session is invalid or expired': '登录状态已过期，请重新登录',
      'item is no longer listed': '该商品已下架',
      'Aliyun STS request failed': '图片服务暂时不可用，请稍后重试',
    };
    return translations[cause.message] ?? cause.message;
  }
  return '操作失败，请稍后重试';
}

export function isUnauthorized(cause: unknown) {
  return typeof cause === 'object'
    && cause !== null
    && 'status' in cause
    && cause.status === 401;
}
