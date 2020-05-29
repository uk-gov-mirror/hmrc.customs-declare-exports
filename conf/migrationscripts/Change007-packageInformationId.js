
function () {
    var generateUuid = function () {
        var dt = new Date().getTime();
        var uuid = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
            var r = (dt + Math.random() * 16) % 16 | 0;
            dt = Math.floor(dt / 16);
            return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
        });
        return uuid;
    };

    var hasAtLeastOneMissingPackageInformationId = function(json) {
        if (json.hasOwnProperty('items')) {
            for (var i = 0; i < json.items.length; i++) {

                var currentItem = json.items[i];
                if (currentItem.hasOwnProperty('packageInformation')) {
                    for (var j = 0; j < currentItem.packageInformation.length; j++) {

                        var currentPackageInformation = currentItem.packageInformation[j];
                        if (!currentPackageInformation.hasOwnProperty('id'))
                            return true;
                    }
                }
            }
        }
        return false;
    };

    if (hasAtLeastOneMissingPackageInformationId(this)) {

        if (this.hasOwnProperty('items')) {
            this.items.forEach(function (item) {

                if (item.hasOwnProperty('packageInformation')) {
                    var packageInfos = item.packageInformation;
                    packageInfos.forEach(function (packageInfo) {
                        packageInfo.id = generateUuid();
                    });
                }
            });
        }

    }

    emit(this['_id'], this);
}