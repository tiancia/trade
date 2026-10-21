import { afterEach, describe, expect, it, vi } from 'vitest';
import { uploadFileToOss } from './api';
import type { UploadIntent } from './api';

const intent: UploadIntent = {
  objectKey: 'marketplace/users/1/phone.png',
  publicUrl: 'https://cdn.example.com/marketplace/users/1/phone.png',
  uploadUrl: 'https://bucket.oss-cn-hangzhou.aliyuncs.com',
  expiration: '2026-10-21T09:00:00Z',
  formFields: {
    key: 'marketplace/users/1/phone.png',
    'content-type': 'image/png',
    'x-oss-content-type': 'image/png',
    'x-oss-object-acl': 'public-read',
    success_action_status: '204',
    policy: 'signed-policy',
    'x-oss-signature-version': 'OSS4-HMAC-SHA256',
    'x-oss-credential': 'temporary-id/20261021/cn-hangzhou/oss/aliyun_v4_request',
    'x-oss-security-token': 'temporary-token',
    'x-oss-date': '20261021T080000Z',
    'x-oss-signature': 'server-signature',
  },
};

describe('marketplace signed uploads', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('posts signed fields unchanged with the file last and no business authentication', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);
    const file = new File(['image'], 'phone.png', { type: 'image/png' });

    await expect(uploadFileToOss(file, intent)).resolves.toBe(intent.publicUrl);

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(intent.uploadUrl);
    expect(options.method).toBe('POST');
    expect(options.credentials).toBe('omit');
    expect(options.headers).toBeUndefined();
    const form = options.body as FormData;
    for (const [key, value] of Object.entries(intent.formFields)) expect(form.get(key)).toBe(value);
    const keys = [...form.keys()];
    expect(keys[keys.length - 1]).toBe('file');
    expect(form.get('file')).toBe(file);
    expect(form.has('accessKeySecret')).toBe(false);
    expect(form.has('Authorization')).toBe(false);
  });

  it('does not return a usable image URL after an OSS policy rejection', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<Error>AccessDenied</Error>', { status: 403 })));
    await expect(uploadFileToOss(new File(['image'], 'phone.png', { type: 'image/png' }), intent))
      .rejects.toThrow('图片上传失败（HTTP 403）');
  });
});
