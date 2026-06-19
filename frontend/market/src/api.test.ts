import { beforeEach, describe, expect, it, vi } from 'vitest';
import { uploadFileToOss } from './api';
import type { UploadIntent } from './api';

const ossMock = vi.hoisted(() => {
  const put = vi.fn();
  const instances: Record<string, unknown>[] = [];

  class FakeOSS {
    put = put;

    constructor(options: Record<string, unknown>) {
      instances.push(options);
    }
  }

  return { FakeOSS, instances, put };
});

vi.mock('ali-oss', () => ({ default: ossMock.FakeOSS }));

describe('marketplace api', () => {
  beforeEach(() => {
    ossMock.instances.length = 0;
    ossMock.put.mockReset();
    ossMock.put.mockResolvedValue({});
  });

  it('uploads marketplace images as public read objects', async () => {
    const intent: UploadIntent = {
      objectKey: 'marketplace/users/1/phone.png',
      publicUrl: 'https://cdn.example.com/marketplace/users/1/phone.png',
      bucket: 'bucket',
      region: 'oss-cn-hangzhou',
      objectAcl: 'public-read',
      credentials: {
        accessKeyId: 'ak',
        accessKeySecret: 'sk',
        securityToken: 'token',
        expiration: '2026-06-19T09:00:00Z',
      },
    };
    const file = new File(['image'], 'phone.png', { type: 'image/png' });

    await expect(uploadFileToOss(file, intent)).resolves.toBe(intent.publicUrl);

    expect(ossMock.instances[0]).toEqual(expect.objectContaining({
      region: 'oss-cn-hangzhou',
      bucket: 'bucket',
      accessKeyId: 'ak',
      accessKeySecret: 'sk',
      stsToken: 'token',
      secure: true,
    }));
    expect(ossMock.put).toHaveBeenCalledWith(intent.objectKey, file, {
      headers: {
        'Content-Type': 'image/png',
        'x-oss-object-acl': 'public-read',
      },
    });
  });
});
