import { useEffect, useState } from 'react';
import type { ChangeEvent, FormEvent } from 'react';
import { Camera, LogIn, UploadCloud } from 'lucide-react';
import { createItem, createUploadIntent, uploadFileToOss } from '../api';
import type { MarketplaceCategory, MarketplaceItem } from '../api';
import { messageOf } from '../errors';

const EMPTY_PUBLISH_FORM = {
  title: '',
  description: '',
  categoryId: '',
  price: '',
};
const ALLOWED_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/gif']);
const MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024;

type PublishPanelProps = {
  active: boolean;
  token: string | undefined;
  isCurrentSession: (token: string) => boolean;
  categories: MarketplaceCategory[];
  onLogin: () => void;
  onPublished: (item: MarketplaceItem) => void;
  onError: (message: string | null) => void;
};

export function PublishPanel({
  active, token, isCurrentSession, categories, onLogin, onPublished, onError,
}: PublishPanelProps) {
  const [publishForm, setPublishForm] = useState(EMPTY_PUBLISH_FORM);
  const [imageFile, setImageFile] = useState<File | null>(null);
  const [imagePreview, setImagePreview] = useState<string | null>(null);
  const [publishing, setPublishing] = useState(false);
  const categoryId = publishForm.categoryId || String(categories[0]?.id ?? '');

  useEffect(() => {
    if (!imageFile) {
      setImagePreview(null);
      return;
    }
    if (typeof URL.createObjectURL !== 'function') return;
    const preview = URL.createObjectURL(imageFile);
    setImagePreview(preview);
    return () => {
      if (typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(preview);
    };
  }, [imageFile]);

  function handleImageChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0] ?? null;
    if (!file) {
      setImageFile(null);
      return;
    }
    if (!ALLOWED_IMAGE_TYPES.has(file.type)) {
      onError('请选择 JPG、PNG、WebP 或 GIF 图片');
      event.target.value = '';
      setImageFile(null);
      return;
    }
    if (file.size <= 0 || file.size > MAX_IMAGE_SIZE_BYTES) {
      onError('图片大小需要在 10 MB 以内');
      event.target.value = '';
      setImageFile(null);
      return;
    }
    onError(null);
    setImageFile(file);
  }

  async function handlePublish(event: FormEvent) {
    event.preventDefault();
    if (!token) {
      onError('请先登录后再发布商品');
      onLogin();
      return;
    }
    if (!isCurrentSession(token)) return;
    if (!imageFile) {
      onError('请选择商品图片');
      return;
    }
    setPublishing(true);
    onError(null);
    try {
      const intent = await createUploadIntent(token, imageFile);
      if (!isCurrentSession(token)) return;
      const imageUrl = await uploadFileToOss(imageFile, intent);
      if (!isCurrentSession(token)) return;
      const created = await createItem(token, {
        title: publishForm.title,
        description: publishForm.description,
        categoryId: Number(categoryId),
        imageUrl,
        price: publishForm.price ? Number(publishForm.price) : null,
      });
      if (!isCurrentSession(token)) return;
      setPublishForm({ ...EMPTY_PUBLISH_FORM, categoryId });
      setImageFile(null);
      onPublished(created);
    } catch (cause) {
      if (isCurrentSession(token)) onError(messageOf(cause));
    } finally {
      setPublishing(false);
    }
  }

  if (!active) return null;
  return (
    <div className="marketplace-sheet-content">
      {!token ? (
        <div className="marketplace-login-required">
          <span><LogIn size={25} /></span>
          <h2>登录后即可发布</h2>
          <p>账号用来管理你的商品，并接收买家的消息。</p>
          <button className="marketplace-primary" type="button" onClick={onLogin}>
            去登录
          </button>
        </div>
      ) : (
        <form onSubmit={(event) => void handlePublish(event)} className="marketplace-form marketplace-publish-form">
          <label>
            <span>商品标题</span>
            <input
              value={publishForm.title}
              required
              maxLength={120}
              placeholder="品牌、型号和关键特点"
              onChange={(event) => setPublishForm({ ...publishForm, title: event.target.value })}
            />
          </label>
          <div className="marketplace-form-row">
            <label>
              <span>分类</span>
              <select
                value={categoryId}
                required
                onChange={(event) => setPublishForm({ ...publishForm, categoryId: event.target.value })}
              >
                {categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
              </select>
            </label>
            <label>
              <span>价格</span>
              <input
                type="number"
                inputMode="decimal"
                min="0"
                max="9999999999.99"
                step="0.01"
                placeholder="留空则面议"
                value={publishForm.price}
                onChange={(event) => setPublishForm({ ...publishForm, price: event.target.value })}
              />
            </label>
          </div>
          <label>
            <span>商品描述</span>
            <textarea
              rows={5}
              value={publishForm.description}
              maxLength={2000}
              placeholder="成色、使用情况、配件和交易方式"
              onChange={(event) => setPublishForm({ ...publishForm, description: event.target.value })}
            />
            <small>{publishForm.description.length}/2000</small>
          </label>
          <label className={`marketplace-upload ${imagePreview ? 'has-image' : ''}`}>
            {imagePreview ? (
              <img src={imagePreview} alt="待上传商品预览" />
            ) : (
              <span className="marketplace-upload-placeholder">
                <Camera size={25} />
                <strong>添加一张实拍图</strong>
                <small>支持 JPG、PNG、WebP、GIF，最大 10 MB</small>
              </span>
            )}
            <span className="marketplace-upload-action">
              <UploadCloud size={16} /> {imageFile ? '更换图片' : '选择图片'}
            </span>
            <input type="file" aria-label="选择商品图片" accept="image/jpeg,image/png,image/webp,image/gif" onChange={handleImageChange} />
          </label>
          <button className="marketplace-primary marketplace-wide" type="submit" disabled={publishing}>
            <UploadCloud size={17} /> {publishing ? '正在上传并发布…' : '确认发布'}
          </button>
        </form>
      )}
    </div>
  );
}
