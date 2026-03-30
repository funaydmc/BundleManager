# BundleManager

Plugin Paper/Spigot dùng để quản lý các bundle config/content cho nhiều plugin khác nhau mà vẫn giữ khả năng gỡ cài đặt an toàn.

BundleManager coi `bundle` là thực thể chính:
- Bundle có thể là file `.zip` hoặc một thư mục trong `plugins/BundleManager/bundles`
- Mỗi bundle có một `id` số: `1`, `2`, `3`, ...
- Một bundle có thể chứa nhiều `package`
- Mỗi package tương ứng với một plugin được hỗ trợ, ví dụ `MythicMobs`, `ItemsAdder`, `ModelEngine`

## Mục tiêu

Khi cài bundle, file của bundle thường bị trộn vào config sẵn có của server. Điều này làm cho việc:
- biết bundle nào đã được cài
- gỡ cài đặt sạch
- rollback khi có lỗi

trở nên rất khó.

BundleManager giải quyết việc này bằng cách:
- quét bundle trong `plugins/BundleManager/bundles`
- chỉ cài các package thuộc plugin đã được hỗ trợ
- lưu toàn bộ metadata cài đặt vào `plugins/BundleManager/data`
- cho phép gỡ từng package hoặc cả bundle bằng dữ liệu đã lưu, kể cả khi bundle gốc không còn

## Đặc điểm chính

- Tự động load toàn bộ bundle khi server bật
- `/bm reload` đồng bộ lại thư mục `bundles`
- Nếu bundle mới xuất hiện thì cài thêm
- Nếu bundle bị xóa khỏi thư mục `bundles` thì tự gỡ bằng data đã lưu
- Nếu SHA-1 của bundle thay đổi thì tự gỡ phần cũ, cài lại phần mới, rồi cập nhật data
- Hỗ trợ variant ở mức bundle và package
- Có hàng chờ conflict khi installer yêu cầu overwrite
- Mọi mutation config phải đi theo hướng cộng thêm và rollback được
- Mặc định giữ nguyên tên file; chỉ đổi tên khi có conflict và installer xác nhận rename-safe

## Yêu cầu

- Java 17+
- Spigot/Paper API 1.18+

Plugin được nạp ở `STARTUP` và khai báo `loadbefore` với các plugin được hỗ trợ để bundle được đồng bộ trước khi các plugin đó dùng config của chúng.

## Plugin được hỗ trợ

Hiện tại BundleManager có installer riêng cho:

- `Blueprints`
- `DeluxeMenus`
- `ItemsAdder`
- `MCPets`
- `MMOItems`
- `ModelEngine`
- `MythicLib`
- `MythicMobs`
- `Nexo`
- `Oraxen`
- `ResourcePack`

## Cấu trúc thư mục

### Bundle input

Người dùng đặt bundle tại:

```text
plugins/BundleManager/bundles
```

Bundle có thể là:

- file `.zip`
- thư mục

Các file không phải `.zip` trong `bundles/` sẽ bị bỏ qua. Khi server load hoặc chạy `/bm reload`, plugin sẽ hiện cảnh báo nếu tồn tại file không phải `.zip`.

### Data persistent

Plugin lưu dữ liệu tại:

```text
plugins/BundleManager/data
```

Các thư mục con chính:

- `packages/`: record cài đặt của từng package
- `preferences/`: trạng thái enable/disable, variant đã chọn, SHA-1
- `backups/`: backup file cũ để uninstall/rollback
- `conflicts/`: hàng chờ overwrite conflict
- `bundle-index.yml`: ánh xạ bundle source -> bundle id số

### Resource pack

Nếu một thư mục có `pack.mcmeta`, BundleManager coi đó là `ResourcePack` và copy vào:

```text
plugins/BundleManager/pack
```

## Cách nhận diện package

Plugin sẽ tìm đệ quy trong bundle:

- nếu gặp thư mục có tên trùng plugin được hỗ trợ, thư mục đó được coi là một package
- nếu gặp thư mục chứa `pack.mcmeta`, thư mục đó được coi là `ResourcePack`
- sau khi tìm thấy package root, plugin không đi sâu thêm vào nhánh đó nữa

Nhờ vậy plugin hỗ trợ tốt các bundle lồng sâu và bundle variant như:

```text
MEGA_BUNDLE/vanilla/MythicMobs/...
MEGA_BUNDLE/model/MythicMobs/...
MEGA_BUNDLE/MythicMobs (vanilla)/...
MEGA_BUNDLE/MythicMobs (model)/...
```

## Variant

BundleManager hỗ trợ:

- `bundle variant`
- `package variant`

Quy tắc ưu tiên:

- `package variant` ưu tiên cao hơn `bundle variant`

Ví dụ:

- bundle chọn `vanilla`
- riêng `MythicMobs` chọn `modded`

thì `MythicMobs` sẽ load `modded`, còn các package khác vẫn load `vanilla`.

### Chọn variant

1. Xem danh sách variant:

```text
/bm variant <bundleId>
```

2. Chọn theo index:

```text
/bm chose <index>
```

Lưu ý: command hiện tại dùng đúng tên `chose` theo code.

## Command

### Liệt kê bundle

```text
/bm list
```

Hiển thị bundle theo `id` số tăng dần.

### Enable bundle hoặc package

```text
/bm enable <bundleId> [package]
```

Ví dụ:

```text
/bm enable 1
/bm enable 1 MythicMobs
```

### Disable bundle hoặc package

```text
/bm disable <bundleId> [package]
```

Khi disable:

- bundle/package sẽ bị gỡ
- trạng thái disable được lưu vào `data/preferences`
- lần sau server bật hoặc `/bm reload`, phần này sẽ không tự load lại

### Reload

```text
/bm reload
```

`reload` không còn gỡ toàn bộ rồi cài lại từ đầu.

Nó sẽ đồng bộ thư mục `bundles` như sau:

- bundle mới: cài thêm
- bundle bị xóa: gỡ bằng data đã lưu
- bundle đổi SHA-1: gỡ phần cũ, cài lại phần mới
- bundle không đổi: giữ nguyên

### Xem conflict overwrite

```text
/bm conflicts
```

### Resolve conflict

```text
/bm resolve <conflictId> overwrite
/bm resolve <conflictId> skip
```

`skip` sẽ bỏ qua conflict và disable package đó để tránh bị queue lại ngay ở lần reload kế tiếp.

### Xem plugin được hỗ trợ

```text
/bm supported
```

## Quy tắc an toàn khi cài

BundleManager được thiết kế cho bài toán can thiệp config của plugin khác, nên nguyên tắc mặc định là bảo thủ:

- chỉ thêm, không tự ý xóa bớt config ngoài phạm vi đã cài
- chỉ chỉnh sửa theo mutation đã được định nghĩa và rollback được
- không overwrite file sẵn có một cách im lặng
- mặc định giữ nguyên tên file
- chỉ rename khi:
  - file đích đã tồn tại
  - installer xác nhận file đó rename-safe
- nếu cần overwrite và installer cho phép yêu cầu overwrite, package sẽ được đưa vào hàng chờ conflict

## Log warning/error khi load

Khi `load` hoặc `/bm reload`, warning/error sẽ được gắn context:

- luôn có tên bundle
- nếu bundle có nhiều package hoặc package là variant thì log sẽ kèm package/variant

Ví dụ:

```text
[LostAssets.zip | DeluxeMenus] Package 'DeluxeMenus' conflicts with existing menu id 'main' ...
[BossPack.zip | MythicMobs - vanilla] Package 'MythicMobs@vanilla' contains duplicate mob id ...
```

Nếu bundle chỉ có một package và không có variant, log có thể chỉ hiện bundle để gọn hơn.

## Hành vi của installer

Mỗi plugin hỗ trợ có installer riêng. Mục tiêu là tránh một hệ thống template quá tổng quát và khó đảm bảo an toàn.

Installer có thể:

- lọc file nào được cài
- đọc ID để cảnh báo duplicate sớm
- rewrite nội dung file nếu plugin đó có reference nội bộ và việc rewrite là an toàn
- khai báo config mutation rollback được
- yêu cầu overwrite conflict queue
- cho phép rename-on-conflict nếu loại file đó an toàn để đổi tên

## Build

```text
./gradlew build
```

## Ghi chú

- BundleManager hiện chỉ xử lý `.zip` và thư mục; không hỗ trợ `.rar`
- Một số plugin có semantics config phức tạp, nên installer sẽ ưu tiên an toàn hơn là cố cài mọi thứ
- Với các plugin chưa có installer, package sẽ bị bỏ qua
